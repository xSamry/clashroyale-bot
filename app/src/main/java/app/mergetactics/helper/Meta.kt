package app.mergetactics.helper

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Troop(val name: String, val cost: Int?, val trait: String?, val tier: String?)
data class Build(val name: String, val ruler: String, val traits: List<String>, val tier: String, val notes: String)
data class Meta(
    val season: String,
    val updated: String,
    val troops: List<Troop>,
    val builds: List<Build>,
    val equipment: List<String>,
    val orbTips: List<String>,
    val shopTop: Float,
    val elixirRegion: FloatArray?
)

object MetaLoader {
    /** Сначала пробуем файл Android/data/app.mergetactics.helper/files/meta.json, иначе встроенный. */
    fun load(ctx: Context): Meta {
        val external = File(ctx.getExternalFilesDir(null), "meta.json")
        if (external.exists()) {
            try {
                return parse(JSONObject(external.readText()))
            } catch (_: Exception) {
                // битый файл — используем встроенный
            }
        }
        val text = ctx.assets.open("meta.json").bufferedReader().use { it.readText() }
        return parse(JSONObject(text))
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

    fun parse(o: JSONObject): Meta {
        val ta = o.getJSONArray("troops")
        val troops = List(ta.length()) { i ->
            val t = ta.getJSONObject(i)
            Troop(
                t.getString("name"),
                if (t.isNull("cost")) null else t.getInt("cost"),
                str(t, "trait"),
                str(t, "tier")
            )
        }
        val ba = o.getJSONArray("builds")
        val builds = List(ba.length()) { i ->
            val b = ba.getJSONObject(i)
            val tr = b.getJSONArray("traits")
            Build(
                b.getString("name"),
                b.optString("ruler", ""),
                List(tr.length()) { j -> tr.getString(j) },
                b.optString("tier", "B"),
                b.optString("notes", "")
            )
        }
        val ea = o.optJSONArray("equipment")
        val equipment = if (ea == null) emptyList() else List(ea.length()) { ea.getString(it) }
        val oa = o.optJSONArray("orbTips")
        val orbTips = if (oa == null) emptyList() else List(oa.length()) { oa.getString(it) }
        val r = o.optJSONObject("regions")
        val shopTop = r?.optDouble("shopTop", 0.78)?.toFloat() ?: 0.78f
        val er = r?.optJSONArray("elixir")
        val elixir = if (er != null && er.length() == 4) FloatArray(4) { er.getDouble(it).toFloat() } else null
        return Meta(o.optString("season", "?"), o.optString("updated", "?"), troops, builds, equipment, orbTips, shopTop, elixir)
    }
}
