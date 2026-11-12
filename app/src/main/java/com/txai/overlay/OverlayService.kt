
package com.txai.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "tx_ai_capture"
    }

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var status: TextView? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var lastBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var recognizer: TextRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val resultCode =
            intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            createNotification()

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            captureThread = HandlerThread("TX-AI-Capture").apply {
                start()
            }
            captureHandler = Handler(captureThread!!.looper)

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                    try {
                        val bitmap = imageToBitmap(image)

                        synchronized(bitmapLock) {
                            lastBitmap?.recycle()
                            lastBitmap = bitmap
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        image.close()
                    }
                },
                captureHandler
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                },
                Handler(mainLooper)
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "TX-AI-Capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )

            showOverlay()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Lỗi: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TX AI Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }

        val notification = builder
            .setContentTitle("TX AI đang hoạt động")
            .setContentText("Đang đọc màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                10,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(10, notification)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val fullBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        fullBitmap.copyPixelsFromBuffer(buffer)

        val result = Bitmap.createBitmap(
            fullBitmap,
            0,
            0,
            image.width,
            image.height
        )

        fullBitmap.recycle()
        return result
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)

            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 28))
                cornerRadius = 18f
                setStroke(2, Color.YELLOW)
            }
        }

        val title = TextView(this).apply {
            text = "TX AI SCREEN READER"
            textSize = 15f
            setTextColor(Color.YELLOW)
        }

        status = TextView(this).apply {
            text = "Đang chờ ảnh màn hình..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
        }

        val analyze = Button(this).apply {
            text = "ĐỌC MÀN HÌNH"
            setOnClickListener {
                analyzeScreen()
            }
        }

        val close = Button(this).apply {
            text = "TẮT BOT"
            setOnClickListener {
                stopSelf()
            }
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
            x = 30
            y = 180
        }

        wm.addView(root, params)
        panel = root
    }

    
private fun analyzeScreen() {
    val bitmap = synchronized(bitmapLock) {
        lastBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    if (bitmap == null) {
        status?.text = "Chưa nhận được ảnh màn hình"
        return
    }

    status?.text = "Đang phân tích lịch sử..."

    try {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )
        }

        recognizer!!.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->

                val width = bitmap.width
                val height = bitmap.height

                val scores = result.textBlocks
                    .flatMap { it.lines }
                    .filter { line ->
                        val box = line.boundingBox
                        box != null &&
                        box.left > width * 0.28 &&
                        box.right < width * 0.75 &&
                        box.top > height * 0.20 &&
                        box.bottom < height * 0.58
                    }
                    .mapNotNull { line ->
                        Regex("\\b(1[0-7]|[4-9])\\b")
                            .find(line.text)?.value?.toIntOrNull()
                    }
                    .take(30)

                status?.text = if (scores.isEmpty()) {
                    "Chưa đọc được tổng điểm. Hãy mở biểu đồ lịch sử."
                } else {
                    val outcomes = scores.map {
                        if (it >= 11) "T" else "X"
                    }

                    "TỔNG ĐIỂM: ${scores.joinToString("-")}\n" +
                    "CẦU: ${outcomes.joinToString("-")}\n" +
                    "Đã đọc ${scores.size} số"
                }
            }
            .addOnFailureListener { error ->
                status?.text = "Lỗi OCR: ${error.message}"
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }

    } catch (e: Exception) {
        bitmap.recycle()
        status?.text = "Lỗi: ${e.message}"
    }
}


        if (bitmap == null) {
            status?.text = "Chưa nhận được ảnh màn hình"
            return
        }

        status?.text = "Đang đọc chữ..."

        try {
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )
            }

            recognizer!!
                .process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    status?.text = if (result.text.isBlank()) {
                        "Không tìm thấy chữ"
                    } else {
                        result.text.take(500)
                    }
                }
                .addOnFailureListener { error ->
                    status?.text = "Lỗi OCR: ${error.message}"
                }
                .addOnCompleteListener {
                    bitmap.recycle()
                }

        } catch (e: Exception) {
            bitmap.recycle()
            status?.text = "Lỗi OCR: ${e.message}"
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()

        projection?.stop()

        synchronized(bitmapLock) {
            lastBitmap?.recycle()
            lastBitmap = null
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        panel?.let {
            if (it.isAttachedToWindow) {
                wm.removeView(it)
            }
        }

        recognizer?.close()
        recognizer = null

        super.onDestroy()
    }
}
