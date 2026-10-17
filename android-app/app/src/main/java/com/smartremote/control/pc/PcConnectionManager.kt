package com.smartremote.control.pc

import android.os.Build
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * مدير الاتصال الشبكي المباشر بين هاتف Android وجهاز كمبيوتر Windows
 */
class PcConnectionManager {

    companion object {
        private const val TAG = "PcConnectionManager"
        const val DEFAULT_PC_PORT = 52840
    }

    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(2, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    var isConnected: Boolean = false
        private set

    var onFrameReceived: ((byteArray: ByteArray, width: Int, height: Int) -> Unit)? = null
    var onSessionStateChanged: ((connected: Boolean, message: String) -> Unit)? = null
    var onLog: ((message: String) -> Unit)? = null

    private var currentSessionId: String = ""

    /**
     * الاتصال بعنوان IP المباشر لجهاز الكمبيوتر مع رمز الـ PIN
     */
    fun connectToPc(pcIpAddress: String, pin: String, port: Int = DEFAULT_PC_PORT) {
        val wsUrl = "ws://$pcIpAddress:$port/"
        onLog?.invoke("جاري إنشاء اتصال مباشر مع الكمبيوتر: $wsUrl ...")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onLog?.invoke("تم فتح مقبس الاتصال بنجاح. جاري إرسال التحية والمصادقة...")

                // 1. إرسال حزمة التعريف HANDSHAKE_HELLO
                sendHandshakeHello()

                // 2. إرسال طلب الجلسة مع الـ PIN
                sendSessionRequest(pin)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingPacket(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onSessionStateChanged?.invoke(false, "تم إغلاق الجلسة: $reason")
                onLog?.invoke("أغلق الكمبيوتر الجلسة: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                val errorMsg = "فشل الاتصال بالكمبيوتر: ${t.localizedMessage ?: "تأكد من تشغيل البرنامج على Windows"}"
                onSessionStateChanged?.invoke(false, errorMsg)
                onLog?.invoke(errorMsg)
            }
        })
    }

    private fun sendHandshakeHello() {
        val json = JSONObject().apply {
            put("type", "HANDSHAKE_HELLO")
            put("deviceId", Build.MODEL)
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL} (Android API ${Build.VERSION.SDK_INT})")
            put("platform", "android")
            put("appVersion", "1.1.0")
        }
        webSocket?.send(json.toString())
    }

    private fun sendSessionRequest(pin: String) {
        currentSessionId = "android_session_${System.currentTimeMillis()}"
        val json = JSONObject().apply {
            put("type", "SESSION_REQUEST")
            put("sessionId", currentSessionId)
            put("initiatorId", Build.MODEL)
            put("targetId", "windows_host")
            put("requestedRole", "full_control")
            put("sessionPin", pin)
            put("targetFps", 30)
        }
        webSocket?.send(json.toString())
    }

    private fun handleIncomingPacket(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            when (json.optString("type")) {
                "SESSION_RESPONSE" -> {
                    val accepted = json.optBoolean("accepted", false)
                    if (accepted) {
                        onSessionStateChanged?.invoke(true, "تم قبول الاتصال والمصادقة بنجاح!")
                        onLog?.invoke("وافق الكمبيوتر على طلب التحكم! بدأت جلسة البث.")
                    } else {
                        val reason = json.optString("rejectionReason", "تم رفض الطلب أو رمز PIN خاطئ")
                        onSessionStateChanged?.invoke(false, reason)
                        onLog?.invoke("رفض الكمبيوتر الاتصال: $reason")
                        disconnect()
                    }
                }
                "SCREEN_FRAME_CHUNK" -> {
                    val base64 = json.optString("payloadBase64")
                    val width = json.optInt("width", 1280)
                    val height = json.optInt("height", 720)
                    if (base64.isNotEmpty()) {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        onFrameReceived?.invoke(bytes, width, height)
                    }
                }
                "DISCONNECT" -> {
                    onSessionStateChanged?.invoke(false, "تم إنهاء الجلسة من قبل الكمبيوتر.")
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تحليل الحزمة: ${e.message}")
        }
    }

    /**
     * إرسال أمر تحكم بالفأرة (إحداثيات نسبية بين 0.0 و 1.0)
     */
    fun sendMouseEvent(subType: String, normX: Float, normY: Float, button: String = "left", wheelDelta: Int = 0) {
        if (!isConnected) return
        val json = JSONObject().apply {
            put("type", "INPUT_EVENT")
            put("sessionId", currentSessionId)
            put("subType", subType) // MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, MOUSE_WHEEL
            put("normalizedX", normX.toDouble())
            put("normalizedY", normY.toDouble())
            put("button", button)
            if (wheelDelta != 0) put("wheelDelta", wheelDelta)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(json.toString())
    }

    /**
     * إرسال نص مكتوب للكمبيوتر
     */
    fun sendTextInput(text: String) {
        if (!isConnected || text.isEmpty()) return
        val json = JSONObject().apply {
            put("type", "INPUT_EVENT")
            put("sessionId", currentSessionId)
            put("subType", "KEY_DOWN")
            put("keyText", text)
            put("timestamp", System.currentTimeMillis())
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        if (isConnected) {
            val json = JSONObject().apply {
                put("type", "DISCONNECT")
                put("sessionId", currentSessionId)
                put("reason", "user_requested")
            }
            webSocket?.send(json.toString())
            webSocket?.close(1000, "User disconnected")
        }
        isConnected = false
        webSocket = null
    }
}
