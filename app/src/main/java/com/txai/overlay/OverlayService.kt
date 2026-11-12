package com.txai.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa cấp quyền cửa sổ nổi", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(30, 30, 30))
                    cornerRadius = 20f
                    setStroke(2, Color.YELLOW)
                }
            }

            val title = TextView(this).apply {
                text = "TX AI OVERLAY"
                textSize = 16f
                setTextColor(Color.YELLOW)
                setPadding(4, 8, 4, 12)
            }

            val status = TextView(this).apply {
                text = "Bot đã sẵn sàng"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(4, 10, 4, 10)
            }

            val analyze = Button(this).apply {
                text = "KIỂM TRA"
                setOnClickListener {
                    status.text = "Overlay hoạt động bình thường"
                }
            }

            val close = Button(this).apply {
                text = "TẮT BOT"
                setOnClickListener { stopSelf() }
            }

            root.addView(title)
            root.addView(status)
            root.addView(analyze)
            root.addView(close)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 180
            }

            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            title.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(root, params)
                        true
                    }
                    else -> false
                }
            }

            wm.addView(root, params)
            panel = root

        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        panel?.let {
            if (it.isAttachedToWindow) wm.removeView(it)
        }
        panel = null
        super.onDestroy()
    }
}
                } else {
                    "Đã đọc được:\n$text"
                }
            }
            .addOnFailureListener { e ->
                status?.text = "Lỗi OCR: ${e.message}"
            }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        lastBitmap?.recycle()
        panel?.let {
            if (it.isAttachedToWindow) wm.removeView(it)
        }
        recognizer.close()
        super.onDestroy()
    }
}
