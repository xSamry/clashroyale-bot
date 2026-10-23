package app.mergetactics.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Положение трёх карточек магазина в долях экрана. Настраивается кнопками ▭ в приложении. */
class ShopLayout(ctx: Context) {
    private val sp = ctx.getSharedPreferences("shop_layout", Context.MODE_PRIVATE)
    var top = sp.getFloat("top", 0.80f)
    var height = sp.getFloat("height", 0.16f)
    var left = sp.getFloat("left", 0.04f)
    var width = sp.getFloat("width", 0.28f)
    var gap = sp.getFloat("gap", 0.02f)

    fun save() {
        sp.edit().putFloat("top", top).putFloat("height", height)
            .putFloat("left", left).putFloat("width", width).putFloat("gap", gap).apply()
    }

    fun rects(): List<RectF> = List(3) { i ->
        val l = left + i * (width + gap)
        RectF(l, top, l + width, top + height)
    }
}

/** Хранилище «отпечатков» карточек: имя юнита → несколько векторов. Файл templates.json. */
class TemplateStore(ctx: Context) {
    private val file = File(ctx.getExternalFilesDir(null), "templates.json")
    private val data = LinkedHashMap<String, MutableList<FloatArray>>()

    init {
        if (file.exists()) {
            try {
                val o = JSONObject(file.readText())
                for (name in o.keys()) {
                    val arr = o.getJSONArray(name)
                    val list = ArrayList<FloatArray>()
                    for (i in 0 until arr.length()) {
                        val v = arr.getJSONArray(i)
                        list.add(FloatArray(v.length()) { v.getDouble(it).toFloat() })
                    }
                    data[name] = list
                }
            } catch (_: Exception) {
                // битый файл — начинаем с пустого
            }
        }
    }

    private fun persist() {
        val o = JSONObject()
        for ((name, list) in data) {
            val arr = JSONArray()
            for (v in list) {
                val a = JSONArray()
                for (f in v) a.put(f.toDouble())
                arr.put(a)
            }
            o.put(name, arr)
        }
        file.writeText(o.toString())
    }

    fun add(name: String, vec: FloatArray): Int {
        val list = data.getOrPut(name) { ArrayList() }
        list.add(vec)
        if (list.size > 6) list.removeAt(0)
        persist()
        return list.size
    }

    fun clear() {
        data.clear()
        persist()
    }

    fun count(): Int {
        var n = 0
        for (l in data.values) n += l.size
        return n
    }

    fun match(vec: FloatArray, threshold: Float): Pair<String, Float>? {
        var best: String? = null
        var bestScore = -1f
        for ((name, list) in data) {
            for (t in list) {
                val s = correlation(vec, t)
                if (s > bestScore) { bestScore = s; best = name }
            }
        }
        return if (best != null && bestScore >= threshold) Pair(best, bestScore) else null
    }

    companion object {
        const val N = 12

        fun correlation(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s / a.size
        }

        /** Вся карточка целиком — для миниатюры в режиме обучения. */
        fun cardBitmap(bmp: Bitmap, r: RectF): Bitmap? {
            val bw = bmp.width
            val bh = bmp.height
            val x = (r.left * bw).toInt().coerceIn(0, bw - 2)
            val y = (r.top * bh).toInt().coerceIn(0, bh - 2)
            val w = (r.width() * bw).toInt().coerceIn(2, bw - x)
            val h = (r.height() * bh).toInt().coerceIn(2, bh - y)
            return try { Bitmap.createBitmap(bmp, x, y, w, h) } catch (_: Exception) { null }
        }

        /**
         * Вектор признаков: центр карточки (без краёв и значков), сжатый до 12×12,
         * нормированный по яркости и контрасту — подсветка/затемнение карточки не мешает.
         */
        fun vector(bmp: Bitmap, r: RectF): FloatArray? {
            val bw = bmp.width
            val bh = bmp.height
            val cw = r.width() * bw
            val ch = r.height() * bh
            val x = (r.left * bw + cw * 0.2f).toInt().coerceIn(0, bw - 2)
            val y = (r.top * bh + ch * 0.15f).toInt().coerceIn(0, bh - 2)
            val w = (cw * 0.6f).toInt().coerceIn(2, bw - x)
            val h = (ch * 0.65f).toInt().coerceIn(2, bh - y)
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            val small = Bitmap.createScaledBitmap(crop, N, N, true)
            val px = IntArray(N * N)
            small.getPixels(px, 0, N, 0, 0, N, N)
            if (small !== crop) small.recycle()
            if (crop !== bmp) crop.recycle()

            val v = FloatArray(N * N * 3)
            for (i in px.indices) {
                v[i * 3] = Color.red(px[i]).toFloat()
                v[i * 3 + 1] = Color.green(px[i]).toFloat()
                v[i * 3 + 2] = Color.blue(px[i]).toFloat()
            }
            var mean = 0f
            for (f in v) mean += f
            mean /= v.size
            var sq = 0f
            for (f in v) sq += (f - mean) * (f - mean)
            val std = sqrt(sq / v.size)
            if (std < 1f) return null // однотонный кусок — пустой слот
            for (i in v.indices) v[i] = (v[i] - mean) / std
            return v
        }
    }
}
