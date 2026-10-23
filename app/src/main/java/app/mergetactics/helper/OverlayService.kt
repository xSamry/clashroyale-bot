package app.mergetactics.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var advisor: Advisor
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var body: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var panel: LinearLayout? = null

    private var realW = 0
    private var realH = 0
    private var capW = 0
    private var capH = 0
    private var running = false
    private var busy = false
    private var debug = false
    private var collapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        advisor = Advisor(MetaLoader.load(this))
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        // Android 14+: колбэк нужно зарегистрировать до создания VirtualDisplay
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        setupCapture()
        buildOverlay()
        running = true
        handler.postDelayed(tick, 1500)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mt", "MT Helper", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "mt")
            .setContentTitle("Merge Tactics Helper работает")
            .setContentText("Чтобы остановить — нажми ✕ на панели")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        realW = dm.widthPixels
        realH = dm.heightPixels
        val scale = minOf(1f, 1080f / realW)
        capW = (realW * scale).toInt()
        capH = (realH * scale).toInt()
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "mt-helper", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
    }

    private fun buildOverlay() {
        val dp = resources.displayMetrics.density
        val p = (6 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD101820.toInt())
            setPadding(p, p, p, p)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val title = TextView(this).apply {
            text = "☰ MT Helper"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(p * 2, 0, p, 0)
            setOnClickListener { onClick() }
        }
        bar.addView(btn("🐞") { debug = !debug })
        bar.addView(btn("–") {
            collapsed = !collapsed
            body.visibility = if (collapsed) android.view.View.GONE else android.view.View.VISIBLE
        })
        bar.addView(btn("✕") { stopSelf() })
        root.addView(bar)

        body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Открой матч Merge Tactics…"
        }
        root.addView(body)

        params = WindowManager.LayoutParams(
            (realW * 0.62f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = (realH * 0.15f).toInt()
        }

        // Перетаскивание панели за заголовок
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = px + (e.rawX - sx).toInt()
                    params.y = py + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, params)
                }
            }
            true
        }

        panel = root
        wm.addView(root, params)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) capture()
            handler.postDelayed(this, 1500)
        }
    }

    private fun capture() {
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap = try {
            val pl = img.planes[0]
            val rowPad = pl.rowStride - pl.pixelStride * capW
            val full = Bitmap.createBitmap(capW + rowPad / pl.pixelStride, capH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(pl.buffer)
            if (rowPad == 0) full else {
                val cropped = Bitmap.createBitmap(full, 0, 0, capW, capH)
                full.recycle()
                cropped
            }
        } finally {
            img.close()
        }
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { onText(it) }
            .addOnCompleteListener { busy = false; bmp.recycle() }
    }

    /** Прямоугольник нашей же панели в координатах кадра — чтобы не читать собственный текст. */
    private fun panelRect(): Rect? {
        val p = panel ?: return null
        if (p.width == 0) return null
        val s = capW.toFloat() / realW
        return Rect(
            (params.x * s).toInt(), (params.y * s).toInt(),
            ((params.x + p.width) * s).toInt(), ((params.y + p.height) * s).toInt()
        )
    }

    private fun onText(text: Text) {
        val own = panelRect()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            val cx = r.exactCenterX()
            val cy = r.exactCenterY()
            if (own != null && own.contains(cx.toInt(), cy.toInt())) continue
            lines.add(OcrLine(line.text, cx / capW, cy / capH))
        }
        body.text = if (debug) {
            lines.take(30).joinToString("\n") { "${(it.cx * 100).toInt()},${(it.cy * 100).toInt()}  ${it.text}" }
                .ifEmpty { "(текст не найден)" }
        } else {
            advisor.advise(lines).joinToString("\n")
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        recognizer.close()
        super.onDestroy()
    }
}
