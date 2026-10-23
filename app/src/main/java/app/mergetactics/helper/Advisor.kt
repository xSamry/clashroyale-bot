package app.mergetactics.helper

import kotlin.math.abs
import kotlin.math.min

/** Строка текста с экрана; cx/cy — центр в долях экрана (0..1). */
data class OcrLine(val text: String, val cx: Float, val cy: Float)

class Advisor(private val meta: Meta) {
    private val tierRank = mapOf("S" to 3.0, "A" to 2.0, "B" to 1.0, "C" to 0.0)
    private val troopNorm = meta.troops.map { it to norm(it.name) }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun findTroop(text: String): Troop? {
        val n = norm(text)
        if (n.length < 3) return null
        // Точное вхождение: берём самое длинное имя (Royal Giant важнее, чем Giant)
        val contained = troopNorm.filter { it.second.length >= 4 && n.contains(it.second) }
            .maxByOrNull { it.second.length }
        if (contained != null) return contained.first
        // Нечёткое совпадение — OCR любит ошибаться на 1–2 буквы
        var best: Troop? = null
        var bestD = Int.MAX_VALUE
        for ((t, tn) in troopNorm) {
            if (tn.length < 5 || abs(tn.length - n.length) > 2) continue
            val d = lev(n, tn)
            val limit = if (tn.length >= 7) 2 else 1
            if (d <= limit && d < bestD) { best = t; bestD = d }
        }
        return best
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun readElixir(lines: List<OcrLine>): Int? {
        val r = meta.elixirRegion ?: return null
        return lines
            .filter { it.cx in r[0]..r[2] && it.cy in r[1]..r[3] }
            .mapNotNull { it.text.trim().toIntOrNull() }
            .firstOrNull { it in 0..99 }
    }

    fun advise(lines: List<OcrLine>, extraShop: List<Troop> = emptyList(), forced: Build? = null): List<String> {
        val shop = ArrayList<Troop>()
        val field = ArrayList<Troop>()
        for (l in lines) {
            val t = findTroop(l.text) ?: continue
            if (l.cy >= meta.shopTop) shop.add(t) else field.add(t)
        }

        for (t in extraShop) shop.add(t)

        val out = ArrayList<String>()
        val elixir = readElixir(lines)
        val owned = field.groupingBy { it.name }.eachCount()
        val traitCount = HashMap<String, Int>()
        for (t in field) {
            val tr = t.trait ?: continue
            traitCount[tr] = (traitCount[tr] ?: 0) + 1
        }

        // 1. Какая сборка подходит к тому, что уже стоит на поле
        val build = forced ?: meta.builds.maxByOrNull { b ->
            b.traits.sumOf { traitCount[it] ?: 0 }.toDouble() + (tierRank[b.tier] ?: 0.0) * 0.3
        }
        val target = build?.traits ?: emptyList()
        if (build != null) {
            val ruler = if (build.ruler.isNotBlank()) " · ${build.ruler}" else ""
            out.add("Сборка: ${build.name} [${build.tier}]$ruler" + (if (forced != null) " 🔒" else ""))
            if (build.notes.isNotBlank()) out.add(build.notes)
        }
        if (elixir != null) out.add("Эликсир: $elixir")

        // 2. Что купить из магазина
        val ranked = shop.distinctBy { it.name }.map { t ->
            val tr = t.trait
            val cnt = owned[t.name] ?: 0
            var score = cnt * 3.0 + (tierRank[t.tier] ?: 0.0) * 0.5
            val why = ArrayList<String>()
            if (cnt > 0) why.add("есть копия — слияние")
            if (tr != null && target.contains(tr)) { score += 2.0; why.add(tr) }
            val cost = t.cost
            if (elixir != null && cost != null && cost > elixir) { score -= 10.0; why.add("не хватает эликсира") }
            Triple(t, score, why)
        }.sortedByDescending { it.second }
        for ((t, _, why) in ranked.take(3)) {
            out.add("Купить: ${t.name}" + if (why.isNotEmpty()) " (${why.joinToString(", ")})" else "")
        }

        // 3. Копии на поле (игра сливает одинаковые сама)
        val copies = owned.filter { it.value >= 2 }.map { "${it.key} ×${it.value}" }
        if (copies.isNotEmpty()) out.add("Копии на поле: " + copies.joinToString(", "))

        // 4. Кого можно продать
        if (field.size >= 4) {
            val weak = field.distinctBy { it.name }.filter { t ->
                val tr = t.trait
                (tr == null || !target.contains(tr)) && (owned[t.name] ?: 0) == 1
            }.take(2)
            if (weak.isNotEmpty()) out.add("Можно продать: " + weak.joinToString(", ") { it.name })
        }

        // 5. Сферы и снаряжение
        val norms = lines.map { norm(it.text) }
        if (norms.any { it.startsWith("orb") || it.endsWith("orb") }) {
            meta.orbTips.take(2).forEach { out.add("Сфера: $it") }
        }
        val eq = meta.equipment.filter { e -> val en = norm(e); norms.any { it.contains(en) } }
        if (eq.isNotEmpty()) out.add("Снаряжение: ${eq.joinToString(", ")} → на кэрри основной черты")

        if (shop.isEmpty() && field.isEmpty()) {
            out.add("Магазин не распознан. Нажми 📷 и научи помощника карточкам (🐞 — отладка).")
        }
        return out
    }
}
