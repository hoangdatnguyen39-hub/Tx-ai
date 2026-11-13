
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
        private const val TOTAL_POINTS = 21
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

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var recognizer: TextRecognizer? = null
    private var analyzing = false

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
                3
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
                mainHandler
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
            .setContentText("Đang đọc biểu đồ lịch sử")
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
                setColor(Color.rgb(25, 25, 25))
                cornerRadius = 18f
                setStroke(2, Color.YELLOW)
            }
        }

        val title = TextView(this).apply {
            text = "TX AI - ĐỌC VÀ PHÂN TÍCH CẦU"
            textSize = 15f
            setTextColor(Color.YELLOW)
        }

        status = TextView(this).apply {
            text = "Đang chờ biểu đồ lịch sử..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }

        val analyze = Button(this).apply {
            text = "ĐỌC 21 KẾT QUẢ VÀ DỰ ĐOÁN"
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
        if (analyzing) return

        analyzing = true
        status?.text = "Đang chụp biểu đồ..."

        panel?.visibility = android.view.View.INVISIBLE

        mainHandler.postDelayed({
            val bitmap = synchronized(bitmapLock) {
                lastBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }

            panel?.visibility = android.view.View.VISIBLE

            if (bitmap == null) {
                analyzing = false
                status?.text = "Không nhận được ảnh màn hình"
                return@postDelayed
            }

            recognizer = recognizer ?: TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            status?.text = "Đang đọc từng ô trong biểu đồ..."
            val scores = mutableListOf<Int?>()
            readSlot(0, bitmap, scores)

        }, 700)
    }

    /*
     * Tọa độ được căn theo ảnh Sun.win bạn gửi.
     * Mỗi lần chỉ OCR một vị trí trong 21 vị trí.
     */
    private fun readSlot(
        index: Int,
        bitmap: Bitmap,
        scores: MutableList<Int?>
    ) {
        if (index >= TOTAL_POINTS) {
            finishReading(bitmap, scores)
            return
        }

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Tâm điểm đầu và cuối của biểu đồ phía trên.
            val firstCenter = 0.3066f
            val lastCenter = 0.7096f

            val centerRatio = firstCenter +
                (lastCenter - firstCenter) *
                index / (TOTAL_POINTS - 1).toFloat()

            val centerX = (width * centerRatio).toInt()
            val halfWidth = (width * 0.0092f).toInt().coerceAtLeast(3)

            val left = (centerX - halfWidth).coerceIn(0, width - 1)
            val right = (centerX + halfWidth).coerceIn(left + 1, width)

            val top = (height * 0.285f).toInt().coerceIn(0, height - 1)
            val bottom = (height * 0.527f).toInt().coerceIn(
                top + 1,
                height
            )

            val crop = Bitmap.createBitmap(
                bitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            val enlarged = Bitmap.createScaledBitmap(
                crop,
                crop.width * 5,
                crop.height * 5,
                true
            )

            crop.recycle()

            recognizer!!.process(
                InputImage.fromBitmap(enlarged, 0)
            )
                .addOnSuccessListener { result ->
                    val regex = Regex("""(?<!\d)(1[0-8]|[3-9])(?!\d)""")

                    val candidates = result.textBlocks
                        .flatMap { it.lines }
                        .flatMap { it.elements }
                        .flatMap { element ->
                            regex.findAll(
                                element.text
                                    .replace("O", "0")
                                    .replace("o", "0")
                                    .replace("I", "1")
                                    .replace("l", "1")
                            ).mapNotNull {
                                it.value.toIntOrNull()
                            }.toList()
                        }
                        .filter { it in 3..18 }

                    scores.add(candidates.firstOrNull())
                }
                .addOnFailureListener {
                    scores.add(null)
                }
                .addOnCompleteListener {
                    enlarged.recycle()

                    if (index == TOTAL_POINTS - 1) {
                        finishReading(bitmap, scores)
                    } else {
                        readSlot(index + 1, bitmap, scores)
                    }
                }

        } catch (e: Exception) {
            scores.add(null)

            if (index == TOTAL_POINTS - 1) {
                finishReading(bitmap, scores)
            } else {
                readSlot(index + 1, bitmap, scores)
            }
        }
    }

    private fun finishReading(
        bitmap: Bitmap,
        scores: List<Int?>
    ) {
        try {
            val validCount = scores.count { it != null }

            if (scores.size != TOTAL_POINTS || validCount != TOTAL_POINTS) {
                val missing = scores.mapIndexedNotNull { index, value ->
                    if (value == null) index + 1 else null
                }

                status?.text =
                    "CHƯA ĐỌC ĐỦ 21 KẾT QUẢ\n" +
                    "Đọc được: $validCount/21\n" +
                    "Ô chưa rõ: ${missing.joinToString(", ")}\n\n" +
                    "Hãy đóng bảng che biểu đồ hoặc thử chụp lại."

                return
            }

            val values = scores.filterNotNull()

            val outcomes = values.map { score ->
                if (score >= 11) "T" else "X"
            }

            status?.text =
                "ĐÃ ĐỌC ĐỦ 21 KẾT QUẢ\n" +
                "Đang phân tích..."

            val prediction = predictNext(outcomes)

            status?.text =
                "ĐÃ ĐỌC: 21/21 KẾT QUẢ\n\n" +
                "TỔNG ĐIỂM:\n" +
                values.joinToString(" - ") +
                "\n\nTÀI / XỈU:\n" +
                outcomes.joinToString(" - ") +
                "\n\n" +
                prediction

        } catch (e: Exception) {
            status?.text = "Lỗi xử lý: ${e.message}"
        } finally {
            bitmap.recycle()
            analyzing = false
        }
    }

    private fun predictNext(outcomes: List<String>): String {
        if (outcomes.size != TOTAL_POINTS) {
            return "Không đủ dữ liệu để dự đoán."
        }

        val history = outcomes.joinToString("")

        // Tìm mẫu lịch sử giống đoạn cuối, rồi xem kết quả sau mẫu đó.
        for (length in 5 downTo 2) {
            val pattern = history.takeLast(length)

            var tai = 0
            var xiu = 0

            for (i in 0 until history.length - length) {
                val oldPattern = history.substring(i, i + length)

                if (oldPattern == pattern) {
                    when (history.getOrNull(i + length)) {
                        'T' -> tai++
                        'X' -> xiu++
                    }
                }
            }

            val total = tai + xiu

            if (total >= 2 && tai != xiu) {
                val choice = if (tai > xiu) "TÀI" else "XỈU"
                val rate = maxOf(tai, xiu) * 100 / total

                return "DỰ ĐOÁN THAM KHẢO: $choice\n" +
                    "Mẫu $pattern xuất hiện trước: $total lần\n" +
                    "Kết quả tiếp theo trùng lựa chọn: $rate%\n" +
                    "Tài: $tai | Xỉu: $xiu"
            }
        }

        // Nếu không tìm được mẫu lặp đủ dữ liệu, chỉ báo xu hướng ngắn hạn.
        val recent = outcomes.takeLast(8)
        val tai = recent.count { it == "T" }
        val xiu = recent.count { it == "X" }

        if (tai == xiu) {
            return "CHƯA CÓ MẪU RÕ RÀNG\n" +
                "8 phiên gần nhất: Tài $tai - Xỉu $xiu\n" +
                "Chưa đưa ra lựa chọn."
        }

        val choice = if (tai > xiu) "TÀI" else "XỈU"

        return "XU HƯỚNG THAM KHẢO: $choice\n" +
            "8 phiên gần nhất: Tài $tai - Xỉu $xiu\n" +
            "Chưa tìm thấy mẫu lặp đủ mạnh."
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

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
        panel = null

        recognizer?.close()
        recognizer = null

        super.onDestroy()
    }
}
