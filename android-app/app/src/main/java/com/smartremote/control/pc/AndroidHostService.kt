package com.smartremote.control.pc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smartremote.control.service.RemoteAccessibilityService
import okhttp3.*
import org.json.JSONObject

/**
 * خدمة تشغيل الهاتف كجهاز مستضيف (Host)
 * تبث شاشة الهاتف لكمبيوتر Windows، وتستقبل نقرات الفأرة لتحويلها لإيماءات لمس
 */
class AndroidHostService : Service() {

    companion object {
        const val CHANNEL_ID = "SmartRemote_PcHost_Channel"
        const val NOTIFICATION_ID = 1002
    }

    private var webSocket: WebSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("الهاتف متصل بكمبيوتر Windows")
            .setContentText("يتم الآن بث الشاشة واستقبال أوامر التحكم من الكمبيوتر")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    /**
     * معالجة أوامر الإدخال الواردة من كمبيوتر Windows
     */
    fun onInputPacketReceivedFromPc(jsonString: String) {
        val json = JSONObject(jsonString)
        if (json.optString("type") == "INPUT_EVENT") {
            val subType = json.optString("subType")
            val normX = json.optDouble("normalizedX", 0.0).toFloat()
            val normY = json.optDouble("normalizedY", 0.0).toFloat()

            val displayMetrics = resources.displayMetrics
            val pixelX = normX * displayMetrics.widthPixels
            val pixelY = normY * displayMetrics.heightPixels

            val accessibilityService = RemoteAccessibilityService.instance

            if (accessibilityService != null) {
                when (subType) {
                    "MOUSE_DOWN" -> {
                        accessibilityService.performTap(pixelX, pixelY, 50)
                    }
                    "ANDROID_GLOBAL_ACTION" -> {
                        when (json.optString("globalAction")) {
                            "BACK" -> accessibilityService.triggerBack()
                            "HOME" -> accessibilityService.triggerHome()
                            "RECENTS" -> accessibilityService.triggerRecents()
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة مشاركة شاشة الهاتف مع الكمبيوتر",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
