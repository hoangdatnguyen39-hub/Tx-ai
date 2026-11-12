
package com.txai.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val captureRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 30)
        }

        val title = TextView(this).apply {
            text = "TX AI SCREEN READER"
            textSize = 22f
        }

        val startButton = Button(this).apply {
            text = "CẤP QUYỀN VÀ BẬT BOT"

            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(
                        this@MainActivity,
                        "Cấp quyền cửa sổ nổi rồi bấm lại",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val manager = getSystemService(
                        MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    startActivityForResult(
                        manager.createScreenCaptureIntent(),
                        captureRequest
                    )
                }
            }
        }

        layout.addView(title)
        layout.addView(startButton)
        setContentView(layout)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(
                    this,
                    OverlayService::class.java
                ).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                }

                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(
                    this,
                    "Đã gửi quyền chụp màn hình",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Bạn chưa cấp quyền chụp màn hình",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
