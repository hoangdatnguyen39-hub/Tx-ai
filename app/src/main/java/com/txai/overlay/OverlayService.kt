
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
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa cấp quyền hiển thị nổi", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(25, 25, 25))
                    cornerRadius = 22f
                    setStroke(2, Color.YELLOW)
                }
            }

            val header = TextView(this).apply {
                text = "TX AI OVERLAY  •  KÉO ĐỂ DI CHUYỂN"
                textSize = 13f
                setTextColor(Color.YELLOW)
                setPadding(4, 8, 4, 14)
            }

            val result = TextView(this).apply {
                text = "Chưa có dữ liệu quét"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(4, 12, 4, 12)
            }

            val analyze = Button(this).apply {
                text = "PHÂN TÍCH"
                setOnClickListener {
                    result.text = "Chưa tích hợp nhận diện màn hình"
                }
            }

            val close = Button(this).apply {
                text = "THU GỌN"
                setOnClickListener {
                    expanded = !expanded
                    result.visibility =
                        if (expanded) android.view.View.VISIBLE
                        else android.view.View.GONE
                    analyze.visibility =
                        if (expanded) android.view.View.VISIBLE
                        else android.view.View.GONE
                    text = if (expanded) "THU GỌN" else "MỞ RỘNG"
                }
            }

            root.addView(header)
            root.addView(result)
            root.addView(analyze)
            root.addView(close)

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 200
            }

            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            header.setOnTouchListener { _, event ->
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
                    else -> true
                }
            }

            wm.addView(root, params)
            panel = root

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Lỗi tạo nút nổi: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        panel?.let {
            if (it.isAttachedToWindow) {
                wm.removeView(it)
            }
        }
        panel = null
        super.onDestroy()
    }
}
