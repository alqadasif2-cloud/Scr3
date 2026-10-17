using System;
using System.Threading;
using System.Windows.Forms;
using SmartRemoteControl.Windows.Services;

namespace SmartRemoteControl.Windows
{
    /// <summary>
    /// نقطة انطلاق برنامج Windows Smart Remote Control
    /// </summary>
    public static class Program
    {
        [STAThread]
        public static void Main(string[] args)
        {
            Application.SetHighDpiMode(HighDpiMode.SystemAware);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            Console.WriteLine("=================================================");
            Console.WriteLine(" Smart Remote Control for Windows (v1.1.0)       ");
            Console.WriteLine(" برمجية التحكم عن بعد والاتصال المحلي مع Android ");
            Console.WriteLine("=================================================");

            var inputService = new InputInjectionService();
            var captureService = new ScreenCaptureService();
            var networkService = new NetworkTransportService(inputService, captureService);

            // بدء الخادم المحلي على المنفذ 52840
            networkService.StartServer(52840);

            // تشغيل واجهة النوافذ الرسومية للتحكم
            Application.Run(new MainForm(networkService));
        }
    }

    /// <summary>
    /// نافذة التحكم الرئيسية لسطح المكتب
    /// </summary>
    public class MainForm : Form
    {
        private readonly NetworkTransportService _networkService;
        private readonly Label _statusLabel;
        private readonly Label _pinLabel;
        private readonly TextBox _logTextBox;
        private readonly Button _disconnectButton;
        private readonly Button _newPinButton;

        public MainForm(NetworkTransportService networkService)
        {
            _networkService = networkService;

            Text = "Smart Remote Control for Windows - v1.1.0";
            Width = 650;
            Height = 480;
            StartPosition = FormStartPosition.CenterScreen;
            RightToLeft = RightToLeft.Yes;
            RightToLeftLayout = true;

            var mainPanel = new Panel { Dock = DockStyle.Fill, Padding = new Padding(15) };

            var headerLabel = new Label
            {
                Text = "نظام التحكم عن بعد المحلي لـ Windows",
                Font = new System.Drawing.Font("Segoe UI", 14, System.Drawing.FontStyle.Bold),
                Dock = DockStyle.Top,
                Height = 35
            };

            var infoPanel = new GroupBox
            {
                Text = "معلومات الاتصال والأمان",
                Dock = DockStyle.Top,
                Height = 110,
                Padding = new Padding(10)
            };

            _pinLabel = new Label
            {
                Text = $"رمز الأمان (PIN) المطلوب للاتصال: {_networkService.DevicePin}",
                Font = new System.Drawing.Font("Segoe UI", 12, System.Drawing.FontStyle.Bold),
                ForeColor = System.Drawing.Color.DarkBlue,
                Location = new System.Drawing.Point(15, 25),
                AutoSize = true
            };

            _statusLabel = new Label
            {
                Text = "الحالة: جاهز بانتظار اتصال هاتف Android على المنفذ 52840",
                Font = new System.Drawing.Font("Segoe UI", 10),
                ForeColor = System.Drawing.Color.DarkGreen,
                Location = new System.Drawing.Point(15, 60),
                AutoSize = true
            };

            _newPinButton = new Button
            {
                Text = "توليد رمز جديد",
                Location = new System.Drawing.Point(480, 22),
                Size = new System.Drawing.Size(120, 30)
            };
            _newPinButton.Click += (s, e) =>
            {
                _networkService.DevicePin = NetworkTransportService.GenerateNewPin();
                _pinLabel.Text = $"رمز الأمان (PIN) المطلوب للاتصال: {_networkService.DevicePin}";
            };

            _disconnectButton = new Button
            {
                Text = "قطع الاتصال فوراً",
                Location = new System.Drawing.Point(480, 58),
                Size = new System.Drawing.Size(120, 30),
                BackColor = System.Drawing.Color.MistyRose
            };
            _disconnectButton.Click += (s, e) => _networkService.DisconnectActiveSession();

            infoPanel.Controls.Add(_pinLabel);
            infoPanel.Controls.Add(_statusLabel);
            infoPanel.Controls.Add(_newPinButton);
            infoPanel.Controls.Add(_disconnectButton);

            var logGroup = new GroupBox
            {
                Text = "سجل أحداث الاتصال وأوامر التحكم",
                Dock = DockStyle.Fill,
                Padding = new Padding(10)
            };

            _logTextBox = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Dock = DockStyle.Fill,
                BackColor = System.Drawing.Color.Black,
                ForeColor = System.Drawing.Color.LimeGreen,
                Font = new System.Drawing.Font("Consolas", 9)
            };
            logGroup.Controls.Add(_logTextBox);

            mainPanel.Controls.Add(logGroup);
            mainPanel.Controls.Add(infoPanel);
            mainPanel.Controls.Add(headerLabel);

            Controls.Add(mainPanel);

            // ربط الأحداث
            _networkService.LogMessage += msg =>
            {
                if (InvokeRequired)
                {
                    BeginInvoke(new Action(() => AppendLog(msg)));
                }
                else
                {
                    AppendLog(msg);
                }
            };

            _networkService.ConnectionStateChanged += (connected, client) =>
            {
                if (InvokeRequired)
                {
                    BeginInvoke(new Action(() => UpdateStatus(connected, client)));
                }
                else
                {
                    UpdateStatus(connected, client);
                }
            };
        }

        private void AppendLog(string message)
        {
            string time = DateTime.Now.ToString("HH:mm:ss");
            _logTextBox.AppendText($"[{time}] {message}{Environment.NewLine}");
        }

        private void UpdateStatus(bool connected, string client)
        {
            if (connected)
            {
                _statusLabel.Text = $"الحالة: متصل نشط مع {client} (جلسة بث وتحكم جارية)";
                _statusLabel.ForeColor = System.Drawing.Color.Green;
            }
            else
            {
                _statusLabel.Text = "الحالة: بانتظار اتصال هاتف Android على المنفذ 52840";
                _statusLabel.ForeColor = System.Drawing.Color.DarkBlue;
            }
        }
    }
}
