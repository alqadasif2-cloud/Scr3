package com.smartremote.control.pc

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خادم WebSocket محلي عالي الكفاءة مدمج في أندرويد
 * يتيح لكومبيوتر Windows أو هاتف Android آخر الاتصال المباشر على الشبكة المحلية
 */
class AndroidLocalServer(private val port: Int = 52840) {

    companion object {
        private const val TAG = "AndroidLocalServer"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()

    private var activeClientSocket: Socket? = null
    private var activeClientOut: OutputStream? = null

    var onPacketReceived: ((jsonString: String) -> Unit)? = null
    var onClientConnected: ((clientAddress: String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "خادم أندرويد المحلي يعمل الآن على المنفذ: $port")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    Log.i(TAG, "اتصال وارد من: ${socket.inetAddress.hostAddress}")

                    threadPool.execute {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "خطأ في خادم أندرويد المحلي: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. قراءة طلب الـ HTTP Handshake
            val reader = input.bufferedReader()
            var line: String?
            var webSocketKey = ""

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    webSocketKey = line!!.substringAfter(":").trim()
                }
            }

            if (webSocketKey.isEmpty()) {
                // ليس طلب WebSocket
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"AndroidRemoteHostReady\"}\r\n".toByteArray())
                output.flush()
                socket.close()
                return
            }

            // 2. إرسال استجابة المصافحة 101 Switching Protocols
            val acceptKey = generateWebSocketAccept(webSocketKey)
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

            synchronized(this) {
                activeClientSocket?.close()
                activeClientSocket = socket
                activeClientOut = output
            }

            onClientConnected?.invoke(socket.inetAddress.hostAddress ?: "Unknown")

            // 3. قراءة إطارات الـ WebSocket (RFC 6455)
            readWebSocketFrames(input)

        } catch (e: Exception) {
            Log.e(TAG, "انقطع اتصال العميل: ${e.message}")
        } finally {
            synchronized(this) {
                if (activeClientSocket == socket) {
                    activeClientSocket = null
                    activeClientOut = null
                }
            }
            try { socket.close() } catch (_: Exception) {}
            onClientDisconnected?.invoke()
        }
    }

    private fun generateWebSocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest((key + WEBSOCKET_GUID).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun readWebSocketFrames(input: InputStream) {
        while (isRunning.get()) {
            val b1 = input.read()
            if (b1 == -1) break

            val opcode = b1 and 0x0F
            if (opcode == 0x08) { // Close frame
                break
            }

            val b2 = input.read()
            if (b2 == -1) break

            val isMasked = (b2 and 0x80) != 0
            var length = (b2 and 0x7F).toLong()

            if (length == 126L) {
                val byte1 = input.read()
                val byte2 = input.read()
                if (byte1 == -1 || byte2 == -1) break
                length = ((byte1 shl 8) or byte2).toLong()
            } else if (length == 127L) {
                var len: Long = 0
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return
                    len = (len shl 8) or (b.toLong() and 0xFF)
                }
                length = len
            }

            val mask = ByteArray(4)
            if (isMasked) {
                var read = 0
                while (read < 4) {
                    val r = input.read(mask, read, 4 - read)
                    if (r == -1) return
                    read += r
                }
            }

            val payload = ByteArray(length.toInt())
            var totalRead = 0
            while (totalRead < length) {
                val r = input.read(payload, totalRead, (length - totalRead).toInt())
                if (r == -1) return
                totalRead += r
            }

            if (isMasked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            if (opcode == 0x01) { // Text frame
                val text = String(payload, Charsets.UTF_8)
                onPacketReceived?.invoke(text)
            }
        }
    }

    /**
     * إرسال حزمة نصية JSON للعميل المتصل
     */
    fun sendText(text: String) {
        synchronized(this) {
            val out = activeClientOut ?: return
            try {
                val payload = text.toByteArray(Charsets.UTF_8)
                val header = mutableListOf<Byte>()

                header.add(0x81.toByte()) // FIN + Opcode 1 (Text)

                val len = payload.size
                when {
                    len <= 125 -> {
                        header.add(len.toByte())
                    }
                    len <= 65535 -> {
                        header.add(126.toByte())
                        header.add(((len ushr 8) and 0xFF).toByte())
                        header.add((len and 0xFF).toByte())
                    }
                    else -> {
                        header.add(127.toByte())
                        for (i in 7 downTo 0) {
                            header.add(((len.toLong() ushr (8 * i)) and 0xFF).toByte())
                        }
                    }
                }

                out.write(header.toByteArray())
                out.write(payload)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في إرسال الإطار: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            activeClientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
        threadPool.shutdownNow()
    }
}
