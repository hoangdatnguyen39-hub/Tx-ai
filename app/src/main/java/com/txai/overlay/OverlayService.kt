
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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var waiting = false
    private var captureTimeout: Runnable? = null

    private data class Dot(
        val x: Int,
        val y: Int,
        val black: Boolean,
        val size: Int
    )

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

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (projection == null &&
            code == Activity.RESULT_OK &&
            data != null
        ) {
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
                        source.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }

                    val image = source.acquireLatestImage()
                    if (image != null) {
                        waiting = false
                        captureTimeout?.let {
                            handler.removeCallbacks(it)
                        }
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
            showResult("LỖI CHỤP MÀN HÌNH")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val paddedWidth = image.width +
                (rowStride - pixelStride * image.width) / pixelStride

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
        } catch (_: Exception) {
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

        // Bỏ ảnh cũ trong hàng đợi.
        try {
            while (true) {
                val old = reader?.acquireLatestImage() ?: break
                old.close()
            }
        } catch (_: Exception) {
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

        handler.postDelayed(captureTimeout!!, 5000)
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

    private fun luminance(color: Int): Int {
        return (
            Color.red(color) * 299 +
            Color.green(color) * 587 +
            Color.blue(color) * 114
        ) / 1000
    }

    private fun findDots(bitmap: Bitmap): List<Dot> {
        val scale = min(
            1.0,
            1000.0 / bitmap.width.toDouble()
        )

        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())

        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        val visited = BooleanArray(w * h)
        val dots = mutableListOf<Dot>()

        val minSize = max(5, (w * 0.008).toInt())
        val maxSize = max(minSize + 2, (w * 0.075).toInt())

        // Chỉ xét vùng trên, bỏ phần biểu tượng xúc xắc phía dưới.
        val maxY = (h * 0.58).toInt()

        val queue = IntArray(w * maxY.coerceAtLeast(1))

        for (polarity in 0..1) {
            java.util.Arrays.fill(visited, false)

            for (y in 1 until maxY - 1) {
                for (x in 1 until w - 1) {
                    val start = y * w + x
                    if (visited[start]) continue

                    val lum = luminance(pixels[start])
                    val matches = if (polarity == 0) {
                        lum < 75
                    } else {
                        lum > 220
                    }

                    if (!matches) continue

                    var head = 0
                    var tail = 0

                    queue[tail++] = start
                    visited[start] = true

                    var minX = x
                    var maxX = x
                    var minCompY = y
                    var maxCompY = y
                    var count = 0

                    while (head < tail) {
                        val pos = queue[head++]
                        val px = pos % w
                        val py = pos / w

                        count++
                        minX = min(minX, px)
                        maxX = max(maxX, px)
                        minCompY = min(minCompY, py)
                        maxCompY = max(maxCompY, py)

                        if (count > maxSize * maxSize * 2) break

                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue

                                val nx = px + dx
                                val ny = py + dy

                                if (nx <= 0 || nx >= w - 1 ||
                                    ny <= 0 || ny >= maxY - 1
                                ) continue

                                val ni = ny * w + nx
                                if (visited[ni]) continue

                                val nl = luminance(pixels[ni])
                                val ok = if (polarity == 0) {
                                    nl < 75
                                } else {
                                    nl > 220
                                }

                                if (ok) {
                                    visited[ni] = true
                                    if (tail < queue.size) {
                                        queue[tail++] = ni
                                    }
                                }
                            }
                        }
                    }

                    val bw = maxX - minX + 1
                    val bh = maxCompY - minCompY + 1

                    if (bw < minSize || bh < minSize ||
                        bw > maxSize || bh > maxSize
                    ) continue

                    val ratio = bw.toFloat() / bh.toFloat()
                    if (ratio < 0.65f || ratio > 1.35f) continue

                    val fill = count.toFloat() / (bw * bh)
                    if (fill < 0.40f || fill > 0.95f) continue

                    dots.add(
                        Dot(
                            x = (minX + maxX) / 2,
                            y = (minCompY + maxCompY) / 2,
                            black = polarity == 0,
                            size = max(bw, bh)
                        )
                    )
                }
            }
        }

        bitmap.recycle()
        return dots
    }

    private fun readChart(bitmap: Bitmap) {
        try {
            val dots = findDots(bitmap)

            if (dots.size < 6) {
                showResult("KHÔNG ĐỌC ĐỦ CẦU")
                return
            }

            // Tìm hàng có nhiều ô nhất.
            val rows = mutableListOf<MutableList<Dot>>()

            for (dot in dots.sortedBy { it.y }) {
                val row = rows.firstOrNull {
                    abs(it.map { d -> d.y }.average() - dot.y) <
                        max(8, dot.size)
                }

                if (row != null) {
                    row.add(dot)
                } else {
                    rows.add(mutableListOf(dot))
                }
            }

            val bestRow = rows
                .filter { it.size >= 6 }
                .maxByOrNull { it.size }

            if (bestRow == null) {
                showResult("KHÔNG TÌM THẤY HÀNG CẦU")
                return
            }

            val history = bestRow
                .distinctBy { it.x / max(1, it.size / 2) }
                .sortedBy { it.x }
                .takeLast(10)
                .map { it.black }

            if (history.size < 6) {
                showResult("KHÔNG ĐỌC ĐỦ CẦU")
                return
            }

            showResult(analyze(history))

        } catch (_: Exception) {
            showResult("LỖI PHÂN TÍCH ẢNH")
        }
    }

    private fun analyze(history: List<Boolean>): String {
        // true = Tài (đen), false = Xỉu (trắng)
        var taiScore = 0.0
        var xiuScore = 0.0

        // Thống kê kết quả chuyển tiếp trong chuỗi đã đọc.
        for (i in 1 until history.size - 1) {
            if (history[i] == history[i - 1]) {
                if (history[i]) taiScore += 0.5
                else xiuScore += 0.5
            }
        }

        // Ưu tiên xu hướng gần nhất.
        val recent = history.takeLast(4)
        val recentTai = recent.count { it }
        val recentXiu = recent.size - recentTai

        taiScore += recentTai * 0.8
        xiuScore += recentXiu * 0.8

        val total = taiScore + xiuScore
        if (total <= 0.0) return "CHƯA ĐỦ DỮ LIỆU"

        val confidence = (
            max(taiScore, xiuScore) / total * 100
        ).toInt().coerceIn(50, 65)

        return if (taiScore >= xiuScore) {
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

            panel?.let {
                if (it.isAttachedToWindow) wm.removeView(it)
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
