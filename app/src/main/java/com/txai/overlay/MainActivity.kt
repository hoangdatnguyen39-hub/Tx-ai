package com.txai.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 50, 32, 32)
        }

        val title = TextView(this).apply {
            text = "TX AI OVERLAY"
            textSize = 25f
        }

        val info = TextView(this).apply {
            text = "Công cụ nhận diện và phân tích lịch sử Tài Xỉu"
            textSize = 16f
        }

        val button = Button(this).apply {
            text = "BẬT NÚT NỔI"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startService(
                        Intent(this@MainActivity, OverlayService::class.java)
                    )
                }
            }
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(button)
        setContentView(layout)
    }
}
