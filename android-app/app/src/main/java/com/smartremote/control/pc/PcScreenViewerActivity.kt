package com.smartremote.control.pc

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * شاشة التحكم الكاملة في سطح مكتب Windows من الهاتف
 * تحول اللمسات والإيماءات إلى أوامر ماوس ولوحة مفاتيح
 */
class PcScreenViewerActivity : Activity() {

    private lateinit var screenImageView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var disconnectButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var hiddenInputEditText: EditText

    private val connectionManager = PcConnectionManager()
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pcIp = intent.getStringExtra("EXTRA_PC_IP") ?: "192.168.1.100"
        val pcPin = intent.getStringExtra("EXTRA_PC_PIN") ?: "123456"

        initUi()
        setupGestureDetector()

        // ربط أحداث الاستقبال
        connectionManager.onFrameReceived = { bytes, _, _ ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                runOnUiThread {
                    screenImageView.setImageBitmap(bitmap)
                }
            }
        }

        connectionManager.onSessionStateChanged = { connected, message ->
            runOnUiThread {
                statusTextView.text = message
                if (!connected) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // بدء الاتصال
        connectionManager.connectToPc(pcIp, pcPin)
    }

    private fun initUi() {
        // يتم بناء واجهة برمجية متجاوبة بدون الحاجة لملفات XML إضافية
        val layout = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        screenImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }

        statusTextView = TextView(this).apply {
            text = "جاري الاتصال بالكمبيوتر..."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
        }

        disconnectButton = Button(this).apply {
            text = "إنهاء"
            setBackgroundColor(android.graphics.Color.parseColor("#CCAA0000"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                connectionManager.disconnect()
                finish()
            }
        }

        keyboardButton = Button(this).apply {
            text = "لوحة المفاتيح"
            setBackgroundColor(android.graphics.Color.parseColor("#CC333333"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                toggleVirtualKeyboard()
            }
        }

        hiddenInputEditText = EditText(this).apply {
            visibility = View.INVISIBLE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrEmpty()) {
                        connectionManager.sendTextInput(s.toString())
                        hiddenInputEditText.setText("")
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(statusTextView)
            addView(keyboardButton)
            addView(disconnectButton)
        }

        layout.addView(screenImageView)
        layout.addView(topBar)
        layout.addView(hiddenInputEditText)

        setContentView(layout)
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val (normX, normY) = getNormalizedCoordinates(e.x, e.y)
                connectionManager.sendMouseEvent("MOUSE_MOVE", normX, normY)
                connectionManager.sendMouseEvent("MOUSE_DOWN", normX, normY, "left")
                connectionManager.sendMouseEvent("MOUSE_UP", normX, normY, "left")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // ضغطة طويلة = نقرة يمين (Right Click)
                val (normX, normY) = getNormalizedCoordinates(e.x, e.y)
                connectionManager.sendMouseEvent("MOUSE_MOVE", normX, normY)
                connectionManager.sendMouseEvent("MOUSE_DOWN", normX, normY, "right")
                connectionManager.sendMouseEvent("MOUSE_UP", normX, normY, "right")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // سحب = تحريك الماوس أو عجلة التمرير
                val (normX, normY) = getNormalizedCoordinates(e2.x, e2.y)
                connectionManager.sendMouseEvent("MOUSE_MOVE", normX, normY)
                return true
            }
        })

        screenImageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun getNormalizedCoordinates(viewX: Float, viewY: Float): Pair<Float, Float> {
        val width = screenImageView.width.toFloat().coerceAtLeast(1f)
        val height = screenImageView.height.toFloat().coerceAtLeast(1f)
        val normX = (viewX / width).coerceIn(0f, 1f)
        val normY = (viewY / height).coerceIn(0f, 1f)
        return Pair(normX, normY)
    }

    private fun toggleVirtualKeyboard() {
        hiddenInputEditText.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(hiddenInputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        connectionManager.disconnect()
        super.onDestroy()
    }
}
