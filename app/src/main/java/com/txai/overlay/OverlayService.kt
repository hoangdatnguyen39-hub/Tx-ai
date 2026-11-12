package com.txai.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var resultText: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 24, 24, 24)
        }

        val titleText = TextView(this).apply {
            text = "TX AI OVERLAY"
            setTextColor(Color.YELLOW)
            textSize = 14f
        }

        resultText = TextView(this).apply {
            text = "Kết quả: Chờ quét..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }

        val analyzeBtn = Button(this).apply {
            text = "PHÂN TÍCH"
            setOnClickListener {
                val isTai = Random.nextBoolean()
                val percent = Random.nextInt(75, 96)
                resultText.text = if (isTai) {
                    "Dự đoán: TÀI ($percent%)"
                } else {
                    "Dự đoán: XỈU ($percent%)"
                }
            }
        }

        val closeBtn = Button(this).apply {
            text = "ĐÓNG"
            setOnClickListener {
                stopSelf()
            }
        }

        overlayView.addView(titleText)
        overlayView.addView(resultText)
        overlayView.addView(analyzeBtn)
        overlayView.addView(closeBtn)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        // Hỗ trợ kéo thả bảng nổi di chuyển trên màn hình
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
