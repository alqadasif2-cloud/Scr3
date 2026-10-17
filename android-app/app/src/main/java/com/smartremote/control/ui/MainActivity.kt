package com.smartremote.control.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartremote.control.R
import com.smartremote.control.compat.DeviceCapabilityMatrix
import com.smartremote.control.pc.AndroidHostService
import com.smartremote.control.pc.PcScreenViewerActivity
import com.smartremote.control.permission.PermissionManagerCompat
import com.smartremote.control.service.RemoteAccessibilityService
import com.smartremote.control.service.ScreenCaptureService

/**
 * الشاشة الرئيسية لتطبيق Smart Remote Control على Android
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var connectToPcBtn: Button
    private lateinit var hostPhoneBtn: Button
    private lateinit var accessibilityBtn: Button

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAST
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, R.string.cast_started, Toast.LENGTH_SHORT).show()
            updateUiState()
        } else {
            Toast.makeText(this, R.string.cast_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun initViews() {
        statusText = findViewById(R.id.tv_system_status)
        ipInput = findViewById(R.id.et_pc_ip)
        pinInput = findViewById(R.id.et_pc_pin)
        connectToPcBtn = findViewById(R.id.btn_connect_pc)
        hostPhoneBtn = findViewById(R.id.btn_host_phone)
        accessibilityBtn = findViewById(R.id.btn_accessibility_settings)
    }

    private fun setupListeners() {
        // الاتصال بكمبيوتر Windows
        connectToPcBtn.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            val pin = pinInput.text.toString().trim()

            if (ip.isEmpty()) {
                ipInput.error = getString(R.string.error_invalid_ip)
                return@setOnClickListener
            }
            if (pin.length != 6) {
                pinInput.error = getString(R.string.error_invalid_pin)
                return@setOnClickListener
            }

            val intent = Intent(this, PcScreenViewerActivity::class.java).apply {
                putExtra("EXTRA_PC_IP", ip)
                putExtra("EXTRA_PC_PIN", pin)
            }
            startActivity(intent)
        }

        // مشاركة شاشة الهاتف واستضافة الجلسة
        hostPhoneBtn.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP_CAST
                }
                startService(stopIntent)
                Toast.makeText(this, R.string.cast_stopped, Toast.LENGTH_SHORT).show()
                updateUiState()
            } else {
                requestScreenCaptureConsent()
            }
        }

        // فتح إعدادات إمكانية الوصول
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestScreenCaptureConsent() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateUiState() {
        val caps = DeviceCapabilityMatrix.inspect(this)
        val isAccEnabled = RemoteAccessibilityService.instance != null

        val statusInfo = buildString {
            append("Android API: ${caps.apiLevel}\n")
            append("إمكانية الوصول (اللمس): ${if (isAccEnabled) "مفعلة ✓" else "معطلة ✕"}\n")
            append("بث الشاشة: ${if (ScreenCaptureService.isRunning) "نشط ✓" else "متوقف"}\n")
            append("دقة البث الموصى بها: ${caps.recommendedStreamingResolution}")
        }
        statusText.text = statusInfo

        hostPhoneBtn.text = if (ScreenCaptureService.isRunning) {
            getString(R.string.btn_stop_cast)
        } else {
            getString(R.string.btn_start_cast)
        }
    }
}
