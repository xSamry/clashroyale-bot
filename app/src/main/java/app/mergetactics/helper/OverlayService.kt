package app.mergetactics.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var meta: Meta
    private lateinit var advisor: Advisor
    private lateinit var shopLayout: ShopLayout
    private lateinit var store: TemplateStore
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var body: TextView
    private lateinit var tuneBox: LinearLayout
    private lateinit var teachBox: LinearLayout
    private lateinit var teachInfo: TextView
    private lateinit var thumbRow: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var panel: LinearLayout? = null
    private var boxView: BoxView? = null
    private var lastBmp: Bitmap? = null
    private var teachBmp: Bitmap? = null
    private var lastLines: List<OcrLine> = emptyList()
    private var shopSlots: List<String?> = listOf(null, null, null)
    private var covered = 0
    private var forcedIdx = -1
    private var teachSel = 0

    private var realW = 0
    private var realH = 0
    private var capW = 0
    private var capH = 0
    private var running = false
    private var busy = false
    private var debug = false
    private var collapsed = false
    private var boxesOn = false
    private var teachMode = false

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

        meta = MetaLoader.load(this)
        advisor = Advisor(meta)
        shopLayout = ShopLayout(this)
        store = TemplateStore(this)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
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

    // ---------- Рамки карточек (режим настройки) ----------

    private inner class BoxView : View(this@OverlayService) {
        private val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW }
        private val label = Paint().apply { color = Color.YELLOW; textSize = 42f }

        override fun onDraw(canvas: Canvas) {
            shopLayout.rects().forEachIndexed { i, r ->
                canvas.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, stroke)
                canvas.drawText("${i + 1}", r.left * width + 10f, r.top * height + 46f, label)
            }
        }
    }

    private fun toggleBoxes() {
        if (teachMode) return
        boxesOn = !boxesOn
        if (boxesOn) {
            val v = BoxView()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp)
            boxView = v
            tuneBox.visibility = View.VISIBLE
        } else {
            boxView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            boxView = null
            tuneBox.visibility = View.GONE
        }
        render()
    }

    // ---------- Обучение ----------

    private fun startTeach() {
        if (boxesOn) { body.text = "Сначала выключи рамки (▭)"; return }
        val src = lastBmp
        if (src == null) { body.text = "Кадр ещё не получен, подожди секунду"; return }
        if (coveredSlots().isNotEmpty()) {
            body.text = "Панель закрывает карточки магазина — перетащи её выше (за ☰)"
            return
        }
        val snap = src.copy(Bitmap.Config.ARGB_8888, false) ?: return
        teachBmp?.recycle()
        teachBmp = snap
        teachMode = true
        teachSel = 0
        body.visibility = View.GONE
        buildTeachUi(snap)
        teachBox.visibility = View.VISIBLE
    }

    private fun buildTeachUi(snap: Bitmap) {
        val dp = resources.displayMetrics.density
        teachBox.removeAllViews()

        teachInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Выбери карточку (жёлтая рамка), потом тапни её имя в списке."
        }
        teachBox.addView(teachInfo)

        thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shopLayout.rects().forEachIndexed { i, r ->
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(4, 4, 4, 4)
            TemplateStore.cardBitmap(snap, r)?.let { iv.setImageBitmap(it) }
            iv.setOnClickListener { teachSel = i; refreshThumbs() }
            thumbRow.addView(iv, LinearLayout.LayoutParams(0, (90 * dp).toInt(), 1f))
        }
        teachBox.addView(thumbRow)
        refreshThumbs()

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val names = meta.troops.map { it.name }.sorted()
        for (chunk in names.chunked(3)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (n in chunk) {
                val tv = TextView(this)
                tv.text = n
                tv.setTextColor(Color.WHITE)
                tv.textSize = 12f
                tv.setPadding(8, 14, 8, 14)
                tv.setBackgroundColor(0x33FFFFFF)
                tv.setOnClickListener { assign(n) }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(2, 2, 2, 2)
                row.addView(tv, lp)
            }
            list.addView(row)
        }
        val sv = ScrollView(this)
        sv.addView(list)
        teachBox.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (170 * dp).toInt()))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val done = TextView(this)
        done.text = "✔ Готово"
        done.setTextColor(Color.WHITE)
        done.textSize = 14f
        done.setPadding(16, 16, 16, 16)
        done.setOnClickListener { endTeach() }
        val wipe = TextView(this)
        wipe.text = "🗑 Очистить всё"
        wipe.setTextColor(Color.WHITE)
        wipe.textSize = 14f
        wipe.setPadding(16, 16, 16, 16)
        wipe.setOnClickListener { store.clear(); teachInfo.text = "Все шаблоны удалены" }
        btns.addView(done)
        btns.addView(wipe)
        teachBox.addView(btns)
    }

    private fun refreshThumbs() {
        for (i in 0 until thumbRow.childCount) {
            thumbRow.getChildAt(i).setBackgroundColor(if (i == teachSel) Color.YELLOW else 0x55FFFFFF)
        }
    }

    private fun assign(name: String) {
        val snap = teachBmp ?: return
        val v = TemplateStore.vector(snap, shopLayout.rects()[teachSel])
        if (v == null) {
            teachInfo.text = "Карточка пустая или однотонная — выбери другую."
            return
        }
        val n = store.add(name, v)
        teachInfo.text = "Сохранено: $name (образцов: $n). Выбери следующую карточку."
        teachSel = (teachSel + 1) % 3
        refreshThumbs()
    }

    private fun endTeach() {
        teachMode = false
        teachBmp?.recycle()
        teachBmp = null
        teachBox.visibility = View.GONE
        if (!collapsed) body.visibility = View.VISIBLE
        body.text = "Образцов: ${store.count()}. Работаю…"
    }

    // ---------- Панель ----------

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
            text = "☰ MT"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun btn(label: String, onClick: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.setPadding(p, p / 2, p, p / 2)
            tv.setOnClickListener { onClick() }
            return tv
        }
        bar.addView(btn("📷") { startTeach() })
        bar.addView(btn("▭") { toggleBoxes() })
        bar.addView(btn("🔁") {
            forcedIdx = if (forcedIdx + 1 >= meta.builds.size) -1 else forcedIdx + 1
            render()
        })
        bar.addView(btn("🐞") { debug = !debug; render() })
        bar.addView(btn("–") {
            collapsed = !collapsed
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
        })
        bar.addView(btn("✕") { stopSelf() })
        root.addView(bar)

        body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Открой матч Merge Tactics…"
        }
        root.addView(body)

        // Настройка рамок карточек
        tuneBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tuneBox.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Совмести жёлтые рамки 1–3 с карточками магазина, потом выключи ▭."
        })
        fun addRow(label: String, get: () -> Float, set: (Float) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(this)
            name.text = label
            name.setTextColor(Color.WHITE)
            name.textSize = 13f
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun step(sign: Float) {
                set((get() + sign * 0.005f).coerceIn(0f, 1f))
                shopLayout.save()
                boxView?.invalidate()
            }
            row.addView(btn("  −  ") { step(-1f) })
            row.addView(btn("  +  ") { step(1f) })
            tuneBox.addView(row)
        }
        addRow("Верх", { shopLayout.top }, { shopLayout.top = it })
        addRow("Высота", { shopLayout.height }, { shopLayout.height = it })
        addRow("Слева", { shopLayout.left }, { shopLayout.left = it })
        addRow("Ширина", { shopLayout.width }, { shopLayout.width = it })
        addRow("Зазор", { shopLayout.gap }, { shopLayout.gap = it })
        root.addView(tuneBox)

        // Обучение
        teachBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        teachInfo = TextView(this)
        thumbRow = LinearLayout(this)
        root.addView(teachBox)

        params = WindowManager.LayoutParams(
            (realW * 0.66f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = (realH * 0.12f).toInt()
        }

        var sx = 0f
        var sy = 0f
        var px = 0
        var py = 0
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

    // ---------- Захват и распознавание ----------

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy && !teachMode) capture()
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
        lastBmp?.recycle()
        lastBmp = bmp
        shopSlots = matchShop(bmp)
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { onText(it) }
            .addOnCompleteListener { busy = false }
    }

    /** Какие карточки магазина закрыты нашей же панелью. */
    private fun coveredSlots(): List<Int> {
        val own = panelRect() ?: return emptyList()
        val res = ArrayList<Int>()
        shopLayout.rects().forEachIndexed { i, r ->
            if (own.intersects(
                    (r.left * capW).toInt(), (r.top * capH).toInt(),
                    (r.right * capW).toInt(), (r.bottom * capH).toInt()
                )
            ) res.add(i)
        }
        return res
    }

    private fun matchShop(bmp: Bitmap): List<String?> {
        covered = 0
        if (boxesOn || store.count() == 0) return listOf(null, null, null)
        val cov = coveredSlots()
        covered = cov.size
        val rects = shopLayout.rects()
        return List(3) { i ->
            if (cov.contains(i)) null
            else {
                val v = TemplateStore.vector(bmp, rects[i])
                if (v == null) null else store.match(v, meta.matchThreshold)?.first
            }
        }
    }

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
        lastLines = lines
        render()
    }

    private fun render() {
        if (boxesOn) {
            body.text = "Режим рамок: совмести 3 рамки с карточками, затем выключи ▭"
            return
        }
        if (teachMode) return
        val extra = shopSlots.mapNotNull { n -> meta.troops.firstOrNull { it.name == n } }
        val forced = if (forcedIdx in meta.builds.indices) meta.builds[forcedIdx] else null
        val out = ArrayList<String>()
        if (debug) {
            out.add("Слоты: " + shopSlots.joinToString(" | ") { it ?: "?" } + " (образцов: ${store.count()})")
            lastLines.take(20).forEach { out.add("${(it.cx * 100).toInt()},${(it.cy * 100).toInt()}  ${it.text}") }
        } else {
            if (store.count() > 0) out.add("Магазин: " + shopSlots.joinToString(" | ") { it ?: "?" })
            if (covered > 0) out.add("⚠ Панель закрывает карточку — перетащи её выше")
            out.addAll(advisor.advise(lastLines, extra, forced))
        }
        body.text = out.joinToString("\n")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        boxView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        recognizer.close()
        super.onDestroy()
    }
}
