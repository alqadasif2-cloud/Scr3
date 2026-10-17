using System;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using SmartRemoteControl.Windows.Models;

namespace SmartRemoteControl.Windows.Services
{
    /// <summary>
    /// خدمة النقل الشبكي لبرنامج Windows عبر WebSocket محلي على المنفذ 52840
    /// مزودة بحماية أمنية من هجمات التخمين ومنع تنفيذ الأوامر قبل المصادقة
    /// </summary>
    public class NetworkTransportService : IDisposable
    {
        private HttpListener? _httpListener;
        private CancellationTokenSource? _cts;
        private WebSocket? _activeClientWebSocket;

        private readonly InputInjectionService _inputService;
        private readonly ScreenCaptureService _captureService;

        // متغيرات الأمان والتحقق من الجلسة
        private bool _isSessionAuthenticated = false;
        private int _failedAttempts = 0;
        private DateTime _lockoutUntil = DateTime.MinValue;
        private int _isSendingFrame = 0;

        public event Action<string>? LogMessage;
        public event Action<bool, string>? ConnectionStateChanged;
        public event Action<SessionRequestPacket, Action<bool>>? SessionRequestReceived;

        public string DevicePin { get; set; } = GenerateNewPin();
        public bool IsConnected => _activeClientWebSocket != null && _activeClientWebSocket.State == WebSocketState.Open && _isSessionAuthenticated;

        public NetworkTransportService(InputInjectionService inputService, ScreenCaptureService captureService)
        {
            _inputService = inputService;
            _captureService = captureService;

            _captureService.FrameCaptured += OnFrameCaptured;
        }

        public static string GenerateNewPin()
        {
            var random = new Random();
            return random.Next(100000, 999999).ToString();
        }

        public void StartServer(int port = 52840)
        {
            _cts = new CancellationTokenSource();
            _httpListener = new HttpListener();
            _httpListener.Prefixes.Add($"http://*:{port}/");

            try
            {
                _httpListener.Start();
                LogMessage?.Invoke($"[Windows Server] تم بدء تشغيل خادم الاتصال المحلي على المنفذ {port}. رمز PIN الحالي: {DevicePin}");
                Task.Run(() => ListenLoop(_cts.Token));
            }
            catch (Exception ex)
            {
                LogMessage?.Invoke($"[Windows Server] تعذر بدء الخادم على المنفذ {port}: {ex.Message}. (قد يتطلب أذونات المسؤول)");
            }
        }

        private async Task ListenLoop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested && _httpListener != null && _httpListener.IsListening)
            {
                try
                {
                    var context = await _httpListener.GetContextAsync();
                    if (context.Request.IsWebSocketRequest)
                    {
                        var wsContext = await context.AcceptWebSocketAsync(null);
                        _activeClientWebSocket = wsContext.WebSocket;
                        _isSessionAuthenticated = false; // لم تتم المصادقة بعد
                        ConnectionStateChanged?.Invoke(false, context.Request.RemoteEndPoint.ToString());
                        LogMessage?.Invoke($"[Windows Server] اتصال وارد جديد من: {context.Request.RemoteEndPoint}. في انتظار إرسال رمز PIN...");

                        _ = Task.Run(() => HandleClientConnection(_activeClientWebSocket, ct));
                    }
                    else
                    {
                        // استجابة فحص الصحة REST
                        byte[] responseBytes = Encoding.UTF8.GetBytes("{\"status\":\"SmartRemoteWindowsReady\",\"protocolVersion\":\"1.1.0\",\"pinRequired\":true}");
                        context.Response.ContentType = "application/json";
                        context.Response.OutputStream.Write(responseBytes, 0, responseBytes.Length);
                        context.Response.Close();
                    }
                }
                catch (Exception ex) when (!ct.IsCancellationRequested)
                {
                    LogMessage?.Invoke($"[Windows Server] خطأ في الاستماع: {ex.Message}");
                }
            }
        }

        private async Task HandleClientConnection(WebSocket ws, CancellationToken ct)
        {
            byte[] buffer = new byte[8192];
            string currentSessionId = string.Empty;

            try
            {
                while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
                {
                    var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", ct);
                        break;
                    }

                    string json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                    using var doc = JsonDocument.Parse(json);
                    string packetType = doc.RootElement.GetProperty("type").GetString() ?? "";

                    switch (packetType)
                    {
                        case "HANDSHAKE_HELLO":
                            var helloAck = new HandshakeHelloPacket
                            {
                                DeviceId = Environment.MachineName,
                                DeviceName = $"{Environment.MachineName} (Windows PC)"
                            };
                            await SendJsonPacket(ws, helloAck);
                            break;

                        case "SESSION_REQUEST":
                            // 1. فحص فترة التجميد المؤقت ضد هجمات التخمين المتكرر (Brute-Force Protection)
                            if (DateTime.UtcNow < _lockoutUntil)
                            {
                                int remainingSec = (int)(_lockoutUntil - DateTime.UtcNow).TotalSeconds;
                                var rejectResp = new SessionResponsePacket
                                {
                                    Accepted = false,
                                    RejectionReason = $"تم تجميد طلبات الجلسة مؤقتاً لتكرار الأخطاء. يرجى الانتظار {remainingSec} ثانية."
                                };
                                await SendJsonPacket(ws, rejectResp);
                                LogMessage?.Invoke($"[الأمان] تم حظر محاولة اتصال أثناء فترة التجميد ({remainingSec} ثانية متبقية).");
                                break;
                            }

                            var req = JsonSerializer.Deserialize<SessionRequestPacket>(json);
                            if (req != null)
                            {
                                currentSessionId = req.SessionId;
                                bool pinMatches = !string.IsNullOrEmpty(DevicePin) && req.SessionPin == DevicePin;

                                if (pinMatches)
                                {
                                    _failedAttempts = 0;
                                    _isSessionAuthenticated = true;

                                    var resp = new SessionResponsePacket
                                    {
                                        SessionId = currentSessionId,
                                        Accepted = true
                                    };
                                    await SendJsonPacket(ws, resp);
                                    ConnectionStateChanged?.Invoke(true, "جلسة معتمدة");
                                    LogMessage?.Invoke($"[Windows Server] تم اعتماد الجلسة بنجاح! رمز PIN مطابق ({req.SessionPin}).");

                                    // بدء بث الشاشة
                                    _captureService.Start(currentSessionId, req.TargetFps);
                                }
                                else
                                {
                                    _failedAttempts++;
                                    _isSessionAuthenticated = false;

                                    if (_failedAttempts >= 3)
                                    {
                                        _lockoutUntil = DateTime.UtcNow.AddSeconds(30);
                                        LogMessage?.Invoke("[الأمان] تم رصد 3 محاولات PIN خاطئة متتالية! تفعيل الحظر المؤقت لمدة 30 ثانية.");
                                    }

                                    var resp = new SessionResponsePacket
                                    {
                                        SessionId = currentSessionId,
                                        Accepted = false,
                                        RejectionReason = "رمز PIN غير صحيح أو تم رفض الطلب."
                                    };
                                    await SendJsonPacket(ws, resp);
                                    LogMessage?.Invoke($"[Windows Server] محاولة اتصال غير مصرح بها برمز PIN خاطئ: ({req.SessionPin}). المحاولات الخاطئة: {_failedAttempts}/3.");
                                }
                            }
                            break;

                        case "INPUT_EVENT":
                            // تدبير أمني صارم: حظر أي أمر تحكم إذا لم تكن الجلسة معتمدة بالـ PIN مسبقاً!
                            if (!_isSessionAuthenticated)
                            {
                                LogMessage?.Invoke("[الأمان] تم حظر أمر تحكم (INPUT_EVENT) لعدم اكتمال المصادقة برمز PIN!");
                                break;
                            }

                            var inputEvent = JsonSerializer.Deserialize<InputEventPacket>(json);
                            if (inputEvent != null && (string.IsNullOrEmpty(currentSessionId) || inputEvent.SessionId == currentSessionId))
                            {
                                _inputService.ProcessInputEvent(inputEvent);
                            }
                            break;

                        case "DISCONNECT":
                            LogMessage?.Invoke("[Windows Server] طلب الطرف الآخر إنهاء الجلسة.");
                            _captureService.Stop();
                            _isSessionAuthenticated = false;
                            break;
                    }
                }
            }
            catch (Exception ex)
            {
                LogMessage?.Invoke($"[Windows Server] انقطع اتصال العميل: {ex.Message}");
            }
            finally
            {
                _isSessionAuthenticated = false;
                _captureService.Stop();
                ConnectionStateChanged?.Invoke(false, string.Empty);
            }
        }

        private async void OnFrameCaptured(ScreenFramePacket framePacket)
        {
            // منع تراكم الإطارات في طابور الذاكرة إذا كانت سرعة الشبكة منخفضة
            if (Interlocked.CompareExchange(ref _isSendingFrame, 1, 0) == 0)
            {
                try
                {
                    if (_isSessionAuthenticated && _activeClientWebSocket != null && _activeClientWebSocket.State == WebSocketState.Open)
                    {
                        await SendJsonPacket(_activeClientWebSocket, framePacket);
                    }
                }
                catch { }
                finally
                {
                    Interlocked.Exchange(ref _isSendingFrame, 0);
                }
            }
        }

        private async Task SendJsonPacket<T>(WebSocket ws, T packet)
        {
            if (ws.State != WebSocketState.Open) return;
            string json = JsonSerializer.Serialize(packet);
            byte[] bytes = Encoding.UTF8.GetBytes(json);
            await ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
        }

        public void DisconnectActiveSession()
        {
            _isSessionAuthenticated = false;
            _captureService.Stop();
            if (_activeClientWebSocket != null && _activeClientWebSocket.State == WebSocketState.Open)
            {
                try
                {
                    _activeClientWebSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Emergency Disconnect", CancellationToken.None);
                }
                catch { }
            }
            _activeClientWebSocket = null;
            ConnectionStateChanged?.Invoke(false, string.Empty);
            LogMessage?.Invoke("[Windows Server] تم قطع الاتصال فورياً بطلب من المستخدم.");
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _captureService.Dispose();
            _httpListener?.Stop();
            _httpListener?.Close();
        }
    }
}
