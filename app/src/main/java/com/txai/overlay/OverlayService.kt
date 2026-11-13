
package com.txai.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.view.View
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
        private const val PREFS_NAME = "tx_ai_memory"
        private const val HISTORY_KEY = "latest_10"
        private const val TOTAL_POINTS = 10
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

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
            .setContentText("Đang lưu 10 kết quả gần nhất")
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

    private fun loadHistory(): List<Int> {
        val saved = prefs.getString(HISTORY_KEY, "") ?: ""

        return saved.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 3..18 }
            .take(TOTAL_POINTS)
    }

    private fun saveHistory(values: List<Int>) {
        prefs.edit()
            .putString(
                HISTORY_KEY,
                values.take(TOTAL_POINTS).joinToString(",")
            )
            .apply()
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
            text = "TX AI - BỘ NHỚ 10 KẾT QUẢ"
            textSize = 15f
            setTextColor(Color.YELLOW)
        }

        val oldHistory = loadHistory()

        status = TextView(this).apply {
            text = if (oldHistory.size == TOTAL_POINTS) {
                "ĐÃ LƯU 10 KẾT QUẢ\n" +
                    oldHistory.joinToString(" - ") +
                    "\n\nBấm đọc để cập nhật."
            } else {
                "Chưa có đủ dữ liệu đã lưu.\nBấm đọc biểu đồ để bắt đầu."
            }

            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        }

        val analyze = Button(this).apply {
            text = "ĐỌC 10 KẾT QUẢ MỚI NHẤT"
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

        panel?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            val bitmap = synchronized(bitmapLock) {
                lastBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }

            panel?.visibility = View.VISIBLE

            if (bitmap == null) {
                analyzing = false
                status?.text = "Không nhận được ảnh màn hình."
                return@postDelayed
            }

            if (recognizer == null) {
                recognizer = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )
            }

            status?.text = "Đang đọc từ phải sang trái..."

            val results = mutableListOf<Int?>()
            readNextPoint(0, bitmap, results)

        }, 700)
    }

    /*
     * Đọc 10 vị trí ngoài cùng bên phải.
     * Thứ tự lưu: mới nhất trước, cũ hơn sau.
     */
    private fun readNextPoint(
        index: Int,
        bitmap: Bitmap,
        results: MutableList<Int?>
    ) {
        if (index >= TOTAL_POINTS) {
            finishReading(bitmap, results)
            return
        }

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Biểu đồ có 21 vị trí từ trái sang phải.
            // Chọn 10 vị trí cuối, tức 10 vị trí bên phải.
            val chartFirst = 0.3066f
            val chartLast = 0.7096f

            val chartIndex = 20 - index

            val centerRatio = chartFirst +
                (chartLast - chartFirst) *
                chartIndex / 20f

            val centerX = (width * centerRatio).toInt()
            val halfWidth = (width * 0.0095f)
                .toInt()
                .coerceAtLeast(4)

            val left = (centerX - halfWidth).coerceIn(0, width - 1)
            val right = (centerX + halfWidth).coerceIn(left + 1, width)

            // Chỉ lấy biểu đồ tổng điểm phía trên.
            val top = (height * 0.235f).toInt().coerceIn(0, height - 1)
            val bottom = (height * 0.425f).toInt().coerceIn(
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

                    results.add(candidates.firstOrNull())
                }
                .addOnFailureListener {
                    results.add(null)
                }
                .addOnCompleteListener {
                    enlarged.recycle()

                    if (index == TOTAL_POINTS - 1) {
                        finishReading(bitmap, results)
                    } else {
                        readNextPoint(index + 1, bitmap, results)
                    }
                }

        } catch (e: Exception) {
            results.add(null)

            if (index == TOTAL_POINTS - 1) {
                finishReading(bitmap, results)
            } else {
                readNextPoint(index + 1, bitmap, results)
            }
        }
    }

    private fun finishReading(
        bitmap: Bitmap,
        results: List<Int?>
    ) {
        try {
            val valid = results.count { it != null }

            if (valid != TOTAL_POINTS) {
                val missing = results.mapIndexedNotNull { index, value ->
                    if (value == null) index + 1 else null
                }

                status?.text =
                    "ĐỌC ĐƯỢC $valid/10 KẾT QUẢ\n" +
                    "Vị trí chưa rõ: ${missing.joinToString(", ")}\n\n" +
                    "Chưa lưu dữ liệu mới.\nHãy thử đọc lại."

                return
            }

            // Đang theo thứ tự mới nhất -> cũ hơn.
            val newestFirst = results.filterNotNull()

            // Lưu lại đúng 10 kết quả mới nhất.
            saveHistory(newestFirst)

            val outcomesNewestFirst = newestFirst.map {
                if (it >= 11) "T" else "X"
            }

            val prediction = predictNext(outcomesNewestFirst)

            status?.text =
                "ĐÃ LƯU 10 KẾT QUẢ MỚI NHẤT\n" +
                "(Đọc từ phải sang trái)\n\n" +
                newestFirst.joinToString(" - ") +
                "\n\nTÀI / XỈU:\n" +
                outcomesNewestFirst.joinToString(" - ") +
                "\n\n" +
                prediction

        } catch (e: Exception) {
            status?.text = "Lỗi xử lý: ${e.message}"
        } finally {
            bitmap.recycle()
            analyzing = false
        }
    }

    private fun predictNext(
        newestFirst: List<String>
    ): String {
        if (newestFirst.size != TOTAL_POINTS) {
            return "Chưa đủ 10 kết quả để phân tích."
        }

        // Chuyển thành thứ tự cũ -> mới để phân tích mẫu.
        val history = newestFirst.reversed()
        val sequence = history.joinToString("")

        for (length in 4 downTo 2) {
            val pattern = sequence.takeLast(length)

            var tai = 0
            var xiu = 0

            for (i in 0 until sequence.length - length) {
                val oldPattern = sequence.substring(i, i + length)

                if (oldPattern == pattern) {
                    when (sequence.getOrNull(i + length)) {
                        'T' -> tai++
                        'X' -> xiu++
                    }
                }
            }

            val total = tai + xiu

            if (total >= 1 && tai != xiu) {
                val choice = if (tai > xiu) "TÀI" else "XỈU"

                return "DỰ ĐOÁN THAM KHẢO: $choice\n" +
                    "Mẫu cầu: $pattern\n" +
                    "Lần trùng trước: $total\n" +
                    "Tài: $tai | Xỉu: $xiu"
            }
        }

        val tai = history.count { it == "T" }
        val xiu = history.count { it == "X" }

        if (tai == xiu) {
            return "CHƯA CÓ XU HƯỚNG RÕ\n" +
                "10 kết quả: Tài $tai - Xỉu $xiu"
        }

        val choice = if (tai > xiu) "TÀI" else "XỈU"

        return "DỰ ĐOÁN THAM KHẢO: $choice\n" +
            "Thống kê 10 kết quả:\n" +
            "Tài: $tai | Xỉu: $xiu\n" +
            "Chưa có mẫu lặp đủ mạnh."
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
