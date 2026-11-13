
package com.txai.overlay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "tx_ai"
    }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var resultText: TextView? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var waiting = false
    private var captureTimeout: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createChannel()

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("TX AI")
            .setContentText("Đang hoạt động")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1001, notification)
        }

        if (panel == null) showOverlay()

        val code = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (projection == null && code == Activity.RESULT_OK && data != null) {
            startCapture(code, data)
        }

        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "TX AI",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        try {
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val metrics = resources.displayMetrics

            projection = manager.getMediaProjection(code, data)

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        display?.release()
                        display = null
                        reader?.close()
                        reader = null
                        projection = null
                    }
                },
                handler
            )

            reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3
            )

            reader?.setOnImageAvailableListener(
                { source ->
                    if (!waiting) {
                        val unused = source.acquireLatestImage()
                        unused?.close()
                        return@setOnImageAvailableListener
                    }

                    val image = source.acquireLatestImage()
                    if (image != null) {
                        waiting = false
                        captureTimeout?.let { handler.removeCallbacks(it) }
                        captureTimeout = null

                        val bitmap = imageToBitmap(image)
                        image.close()

                        showOverlayAgain()

                        if (bitmap != null) {
                            readChart(bitmap)
                        } else {
                            showResult("ẢNH KHÔNG HỢP LỆ")
                        }
                    }
                },
                handler
            )

            display = projection?.createVirtualDisplay(
                "TX_AI_SCREEN",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                handler
            )

        } catch (e: Exception) {
            showResult("LỖI: ${e.javaClass.simpleName}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth =
                image.width + (rowStride - pixelStride * image.width) / pixelStride

            val full = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)

            val result = Bitmap.createBitmap(
                full, 0, 0, image.width, image.height
            )
            full.recycle()
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 25))
                cornerRadius = 18f
                setStroke(2, Color.YELLOW)
            }
        }

        val title = TextView(this).apply {
            text = "TX AI"
            textSize = 16f
            setTextColor(Color.YELLOW)
        }

        resultText = TextView(this).apply {
            text = "SẴN SÀNG"
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }

        val predict = Button(this).apply {
            text = "DỰ ĐOÁN"
            setOnClickListener { captureAndPredict() }
        }

        val close = Button(this).apply {
            text = "TẮT BOT"
            setOnClickListener { stopSelf() }
        }

        root.addView(title)
        root.addView(resultText)
        root.addView(predict)
        root.addView(close)

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

    private fun captureAndPredict() {
        if (waiting) return

        if (projection == null || reader == null) {
            showResult("CHƯA CÓ QUYỀN CHỤP")
            return
        }

        waiting = true
        resultText?.text = "ĐANG ĐỌC..."

        panel?.let {
            if (it.isAttachedToWindow) wm.removeView(it)
        }

        captureTimeout = Runnable {
            if (waiting) {
                waiting = false
                showOverlayAgain()
                showResult("HẾT THỜI GIAN CHỜ ẢNH")
            }
        }

        handler.postDelayed(captureTimeout!!, 4000)
    }

    private fun showOverlayAgain() {
        handler.post {
            val view = panel ?: return@post

            try {
                if (!view.isAttachedToWindow) {
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
                    wm.addView(view, params)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readChart(bitmap: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val values = mutableListOf<Pair<Int, Int>>()

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue

                        if (rect.centerY() > bitmap.height * 0.58f) {
                            continue
                        }

                        val number = line.text.trim().toIntOrNull()
                            ?: continue

                        if (number in 3..18) {
                            values.add(rect.centerX() to number)
                        }
                    }
                }

                val history = values
                    .sortedBy { it.first }
                    .takeLast(21)
                    .mapNotNull {
                        when (it.second) {
                            in 4..10 -> false
                            in 11..17 -> true
                            else -> null
                        }
                    }
                    .takeLast(10)

                bitmap.recycle()

                if (history.size < 6) {
                    showResult("KHÔNG ĐỌC ĐỦ CẦU")
                } else {
                    showResult(analyze(history))
                }
            }
            .addOnFailureListener {
                bitmap.recycle()
                showResult("LỖI NHẬN DIỆN")
            }
    }

    private fun analyze(history: List<Boolean>): String {
        val tai = history.count { it }
        val xiu = history.size - tai

        val p = (tai + 1.0) / (history.size + 2.0)
        val confidence = (
            maxOf(p, 1.0 - p) * 100
        ).toInt().coerceIn(50, 65)

        return if (p >= 0.5) {
            "TÀI — $confidence%"
        } else {
            "XỈU — $confidence%"
        }
    }

    private fun showResult(message: String) {
        handler.post {
            resultText?.apply {
                text = message
                setTextColor(
                    when {
                        message.startsWith("TÀI") -> Color.YELLOW
                        message.startsWith("XỈU") -> Color.CYAN
                        else -> Color.WHITE
                    }
                )
            }
            showOverlayAgain()
        }
    }

    override fun onDestroy() {
        waiting = false
        captureTimeout?.let { handler.removeCallbacks(it) }

        try {
            display?.release()
            reader?.close()
            projection?.stop()
            recognizer.close()

            panel?.let {
                if (it.isAttachedToWindow) wm.removeView(it)
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
