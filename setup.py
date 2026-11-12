import os

os.makedirs("app/src/main/java/com/txai/overlay", exist_ok=True)
os.makedirs("app/src/main/res/layout", exist_ok=True)
ns = "".join(["http", "://", "schemas.", "android", ".com/apk/res/android"])

with open("app/build.gradle", "w") as f:
    f.write("""plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
android {
    namespace 'com.txai.overlay'
    compileSdk 35
    defaultConfig {
        applicationId 'com.txai.overlay'
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName '1.0'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}
dependencies {
    implementation 'com.google.mlkit:text-recognition:16.0.1'
}""")

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="{ns}">
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application android:allowBackup="true" android:label="Tx-ai" android:theme="@android:style/Theme.Material.Light.DarkActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service android:name=".FloatingBotService" android:enabled="true" android:exported="false" android:foregroundServiceType="mediaProjection" />
    </application>
</manifest>""")

with open("app/src/main/res/layout/layout_floating_bot.xml", "w") as f:
    f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="{ns}" android:layout_width="wrap_content" android:layout_height="wrap_content" android:background="#E6111111" android:orientation="vertical" android:padding="14dp">
    <TextView android:id="@+id/tvStatus" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Tx-AI: Dang quet..." android:textColor="#00FF00" android:textSize="16sp" android:textStyle="bold" />
    <TextView android:id="@+id/tvHistory" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="4dp" android:text="Cau: Cho van moi" android:textColor="#FFFFFF" android:textSize="13sp" />
    <Button android:id="@+id/btnClose" android:layout_width="wrap_content" android:layout_height="36dp" android:layout_marginTop="8dp" android:text="Tat Bot" android:textSize="12sp" />
</LinearLayout>""")

with open("app/src/main/java/com/txai/overlay/MainActivity.kt", "w") as f:
    f.write("""package com.txai.overlay
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var mpm: MediaProjectionManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 101)
        }
        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }
        val btnPerm = Button(this).apply {
            text = "1. Cap quyen Hien thi noi"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val btnStart = Button(this).apply {
            text = "2. Bat Bot Auto Soi Cau"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Ban chua bat nut so 1!", Toast.LENGTH_SHORT).show()
                } else {
                    startActivityForResult(mpm.createScreenCaptureIntent(), 100)
                }
            }
        }
        layout.addView(btnPerm)
        layout.addView(btnStart)
        setContentView(layout)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            val i = Intent(this, FloatingBotService::class.java).apply {
                putExtra("CODE", res)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            moveTaskToBack(true)
        }
    }
}""")

with open("app/src/main/java/com/txai/overlay/FloatingBotService.kt", "w") as f:
    f.write("""package com.txai.overlay
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class FloatingBotService : Service(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private lateinit var view: View
    private lateinit var tts: TextToSpeech
    private var ready = false
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var ir: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val history = mutableListOf<String>()
    private var lastDetected = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cid = "tx_ai_auto"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(cid, "Tx-AI Auto", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, cid).setContentTitle("Tx-AI Auto dang quet").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        } else {
            Notification.Builder(this).setContentTitle("Tx-AI Auto dang quet").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        val code = intent?.getIntExtra("CODE", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("DATA")
        if (code == Activity.RESULT_OK && data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = mpm.getMediaProjection(code, data)
            startScreenScan()
        }
        return START_NOT_STICKY
    }

    private fun setupOverlay() {
        view = LayoutInflater.from(this).inflate(R.layout.layout_floating_bot, null)
        val flag = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(-2, -2, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, p)
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) { ix = p.x; iy = p.y; tx = e.rawX; ty = e.rawY; true }
            else if (e.action == MotionEvent.ACTION_MOVE) { p.x = ix + (e.rawX - tx).toInt(); p.y = iy + (e.rawY - ty).toInt(); wm.updateViewLayout(view, p); true }
            else false
        }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { stopSelf() }
    }

    private fun startScreenScan() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2
        val h = dm.heightPixels / 2
        ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = mp?.createVirtualDisplay("TxScan", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, null)
        handler.postDelayed(scanRunnable, 2500)
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            val img = ir?.acquireLatestImage()
            if (img != null) {
                val plane = img.planes.first()
                val buf = plane.buffer
                val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                img.close()
                analyzeScreen(bmp)
            }
            handler.postDelayed(this, 2500)
        }
    }

    private fun analyzeScreen(bmp: Bitmap) {
        val image = InputImage.fromBitmap(bmp, 0)
        recognizer.process(image).addOnSuccessListener { visionText ->
val raw = visionText.text.uppercase()
val detected = when {
raw.contains("TAI") || raw.contains("TÀI") -> "T"
raw.contains("XIU") || raw.contains("XỈU") -> "X"
else -> ""
}
if (detected.isNotEmpty() && detected != lastDetected) {
lastDetected = detected
history.add(detected)
if (history.size > 10) history.removeAt(0)
val nextCall = predictNext()
val textCall = if (nextCall == "T") "Tai" else "Xiu"
view.findViewById(R.id.tvStatus).text = "Chot: $textCall"
view.findViewById(R.id.tvHistory).text = "Cau: ${history.joinToString("-")}"
if (ready) tts.speak("Van sau danh $textCall", TextToSpeech.QUEUE_FLUSH, null, null)
}
}
}
private fun predictNext(): String {
if (history.size < 2) return history.lastOrNull() ?: "T"
val last = history.last()
val prev = history[history.size - 2]
return if (last == prev) last else if (last == "T") "X" else "T"
}
override fun onInit(s: Int) {
if (s == 0) ready = tts.setLanguage(Locale("vi", "VN")) >= 0
}
override fun onDestroy() {
super.onDestroy()
handler.removeCallbacks(scanRunnable)
vd?.release()
ir?.close()
mp?.stop()
if (::view.isInitialized) wm.removeView(view)
if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
}
}""")
