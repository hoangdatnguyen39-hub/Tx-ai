package com.txai.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object ScreenCapture {
    const val REQUEST_CODE = 1001
    var resultCode: Int = Activity.RESULT_CANCELED
    var resultData: Intent? = null

    fun requestPermission(activity: Activity) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    fun hasPermission(): Boolean {
        return resultCode == Activity.RESULT_OK && resultData != null
    }
}

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
            text = "Công cụ phân tích Tài Xỉu"
            textSize = 16f
        }

        val captureButton = Button(this).apply {
            text = "1. CẤP QUYỀN CHỤP MÀN HÌNH"
            setOnClickListener {
                ScreenCapture.requestPermission(this@MainActivity)
            }
        }

        val button = Button(this).apply {
            text = "2. BẬT NÚT NỔI"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val serviceIntent = Intent(this@MainActivity, OverlayService::class.java)
                    if (ScreenCapture.hasPermission()) {
                        serviceIntent.putExtra("resultCode", ScreenCapture.resultCode)
                        serviceIntent.putExtra("data", ScreenCapture.resultData)
                    }
                    startService(serviceIntent)
                    Toast.makeText(this@MainActivity, "Đã bật nút nổi!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(info)
        layout.addView(captureButton)
        layout.addView(button)

        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCapture.REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCapture.resultCode = resultCode
                ScreenCapture.resultData = data
                Toast.makeText(this, "Đã cấp quyền chụp màn hình thành công!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bạn đã từ chối quyền chụp màn hình!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
