
package com.txai.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var resultText: TextView? = null
    private val random = SecureRandom()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (panel == null) {
            showOverlay()
            predict()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)

            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 25))
                cornerRadius = 20f
                setStroke(2, Color.YELLOW)
            }
        }

        val title = TextView(this).apply {
            text = "TX AI - DỰ ĐOÁN TÀI XỈU"
            textSize = 17f
            setTextColor(Color.YELLOW)
        }

        resultText = TextView(this).apply {
            text = "Đang khởi tạo..."
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(0, 22, 0, 22)
        }

        val note = TextView(this).apply {
            text = "Lựa chọn ngẫu nhiên - không đảm bảo chính xác"
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val predictButton = Button(this).apply {
            text = "DỰ ĐOÁN LẠI"
            setOnClickListener {
                predict()
            }
        }

        val closeButton = Button(this).apply {
            text = "TẮT BOT"
            setOnClickListener {
                stopSelf()
            }
        }

        root.addView(title)
        root.addView(resultText)
        root.addView(note)
        root.addView(predictButton)
        root.addView(closeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 180
        }

        wm.addView(root, params)
        panel = root
    }

    private fun predict() {
        val isTai = random.nextBoolean()

        resultText?.apply {
            text = if (isTai) {
                "DỰ ĐOÁN: TÀI"
            } else {
                "DỰ ĐOÁN: XỈU"
            }

            setTextColor(
                if (isTai) Color.YELLOW else Color.CYAN
            )
        }
    }

    override fun onDestroy() {
        panel?.let {
            if (it.isAttachedToWindow) {
                wm.removeView(it)
            }
        }

        panel = null
        resultText = null

        super.onDestroy()
    }
}
