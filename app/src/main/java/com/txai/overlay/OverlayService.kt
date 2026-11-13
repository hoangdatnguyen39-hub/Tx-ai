
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "tx_ai_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var resultText: TextView? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var capturing = false
    private var captureAttempts = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TX AI đang hoạt động")
            .setContentText("Sẵn sàng đọc cầu")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (panel == null) {
            showOverlay()
        }

        val resultCode = intent?.getIntExtra(
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

        if (projection == null && resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TX AI",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startScreenCapture(
        resultCode: Int,
        data: Intent
    ) {
        try {
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "TX_AI_CAPTURE",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                handler
            )

        } catch (e: Exception) {
            resultText?.text = "LỖI CHỤP MÀN HÌNH"
        }
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)

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
            setPadding(0, 16, 0, 16)
        }

        val predictButton = Button(this).apply {
            text = "DỰ ĐOÁN"
            setOnClickListener {
                captureAndPredict()
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

    private fun captureAndPredict() {
        if (capturing) return

        if (projection == null || reader == null) {
            resultText?.text = "CHƯA CÓ QUYỀN CHỤP"
            return
        }

        capturing = true
        captureAttempts = 0
        resultText?.text = "ĐANG ĐỌC CẦU..."

        panel?.let {
            if (it.isAttachedToWindow) {
                wm.removeView(it)
            }
        }

        handler.postDelayed({
            captureFrame()
        }, 500)
    }

    private fun captureFrame() {
        try {
            val image = reader?.acquireLatestImage()

            if (image == null) {
                captureAttempts++

                if (captureAttempts < 3) {
                    handler.postDelayed({
                        captureFrame()
                    }, 300)
                } else {
                    finishCapture("KHÔNG CHỤP ĐƯỢC MÀN HÌNH")
                }
                return
            }

            val bitmap = imageToBitmap(image)
            image.close()

            showOverlayAgain()

            if (bitmap == null) {
                finishCapture("ẢNH KHÔNG HỢP LỆ")
                return
            }

            readChart(bitmap)

        } catch (e: Exception) {
            showOverlayAgain()
            finishCapture("LỖI ĐỌC MÀN HÌNH")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val paddedWidth =
                image.width + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            buffer.rewind()
            fullBitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(
                fullBitmap,
                0,
                0,
                image.width,
                image.height
            )

            fullBitmap.recycle()
            cropped

        } catch (e: Exception) {
            null
        }
    }

    private fun showOverlayAgain() {
        if (panel == null) return

        try {
            if (panel?.isAttachedToWindow != true) {
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

                wm.addView(panel, params)
            }
        } catch (_: Exception) {
        }
    }

    private fun readChart(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { result ->

                val candidates = mutableListOf<Pair<Int, Int>>()

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue

                        // Chỉ lấy vùng phía trên màn hình.
                        if (rect.centerY() > bitmap.height * 0.58f) {
                            continue
                        }

                        val value = line.text.trim().toIntOrNull()
                            ?: continue

                        if (value !in 3..18) continue

                        candidates.add(
                            Pair(rect.centerX(), value)
                        )
                    }
                }

                val sorted = candidates.sortedBy { it.first }

                val unique = mutableListOf<Pair<Int, Int>>()

                for (item in sorted) {
                    val previous = unique.lastOrNull()

                    if (previous == null ||
                        item.first - previous.first > 12
                    ) {
                        unique.add(item)
                    }
                }

                val outcomes = unique
                    .takeLast(21)
                    .mapNotNull { item ->
                        when (item.second) {
                            in 4..10 -> false
                            in 11..17 -> true
                            else -> null
                        }
                    }
                    .takeLast(10)

                bitmap.recycle()

                if (outcomes.size < 6) {
                    finishCapture("KHÔNG ĐỌC ĐỦ CẦU")
                    return@addOnSuccessListener
                }

                val prediction = analyze(outcomes)

                finishCapture(prediction)

            }
            .addOnFailureListener {
                bitmap.recycle()
                finishCapture("ĐỌC CẦU THẤT BẠI")
            }
    }

    private fun analyze(history: List<Boolean>): String {
        val lastA = history[history.size - 2]
        val lastB = history.last()

        var taiAfterPattern = 0
        var xiuAfterPattern = 0

        for (i in 0 until history.size - 2) {
            if (history[i] == lastA &&
                history[i + 1] == lastB
            ) {
                if (history[i + 2]) {
                    taiAfterPattern++
                } else {
                    xiuAfterPattern++
                }
            }
        }

        val matches = taiAfterPattern + xiuAfterPattern

        val taiProbability: Double

        if (matches >= 2) {
            taiProbability =
                (taiAfterPattern + 1.0) / (matches + 2.0)
        } else {
            val taiCount = history.count { it }
            taiProbability =
                (taiCount + 1.0) / (history.size + 2.0)
        }

        val confidence = (
            maxOf(taiProbability, 1.0 - taiProbability) * 100
        ).toInt().coerceIn(50, 65)

        return if (taiProbability >= 0.5) {
            "TÀI — $confidence%"
        } else {
            "XỈU — $confidence%"
        }
    }

    private fun finishCapture(message: String) {
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
        capturing = false
    }

    override fun onDestroy() {
        try {
            virtualDisplay?.release()
            reader?.close()
            projection?.stop()
            recognizer.close()

            panel?.let {
                if (it.isAttachedToWindow) {
                    wm.removeView(it)
                }
            }
        } catch (_: Exception) {
        }

        panel = null
        super.onDestroy()
    }
}
