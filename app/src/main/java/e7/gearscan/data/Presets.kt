package e7.gearscan.data

import e7.gearscan.calc.HeroBase
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.Objectives
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A community build cluster from Fribbels' hero library: same active sets. */
class Preset(
    val label: String,
    val share: Int,
    val sets: Map<String, Int>,
    val artifact: String?,
    val median: DoubleArray,
    /** One real player's build from the RTA helper, not a cluster median. */
    val rta: Boolean = false,
)

class PresetTabs(val mostUsed: List<Preset>, val topRanked: List<Preset>, val fastest: List<Preset>, val total: Int,
                 val date: String?, val rta: List<Preset> = emptyList()) {
    /** Tabs in sheet order: the RTA helper's 8 samples first when the hero has them. */
    val names get() = (if (rta.isNotEmpty()) listOf("RTA") else emptyList()) + listOf("Most used", "Top ranked", "Fastest")

    fun tab(i: Int): List<Preset> = when (names.getOrNull(i)) { "RTA" -> rta; "Most used" -> mostUsed; "Top ranked" -> topRanked; else -> fastest }
}

object Presets {
    private const val DAY_MS = 24 * 3600 * 1000L

    /** Builds for one hero, cached for a day in [dir]. Network errors fall back to the cache. */
    fun load(dir: File, content: Content, code: String, name: String, allowNetwork: Boolean): PresetTabs? {
        val f = File(dir, "$code.json")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS
        if (!fresh && allowNetwork) {
            runCatching { fetch(content.buildsUrl, name) }.getOrNull()?.let { body ->
                if (JSONObject(body).has("data")) { dir.mkdirs(); f.writeText(body) }
            }
        }
        val rta = loadRta(dir, content, code, allowNetwork)
        val tabs = if (f.exists()) runCatching { parse(JSONObject(f.readText()), content) }.getOrNull() else null
        return when {
            tabs != null -> PresetTabs(tabs.mostUsed, tabs.topRanked, tabs.fastest, tabs.total, tabs.date, rta)
            rta.isNotEmpty() -> PresetTabs(emptyList(), emptyList(), emptyList(), 0, null, rta)
            else -> null
        }
    }

    /** The RTA helper's 8 community samples for a hero (the same file its site shows), cached for a day; empty if it has none. */
    private fun loadRta(dir: File, content: Content, code: String, allowNetwork: Boolean): List<Preset> {
        val url = content.rtaBuildsUrl ?: return emptyList()
        val f = File(dir, "rta_$code.json")
        val miss = File(dir, "rta_$code.none")
        val fresh = (f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS) ||
            (miss.exists() && System.currentTimeMillis() - miss.lastModified() < DAY_MS)
        if (!fresh && allowNetwork) runCatching {
            val c = URL(url.replace("{code}", code)).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            when (c.responseCode) {
                200 -> { dir.mkdirs(); f.writeText(c.inputStream.bufferedReader().use { it.readText() }); miss.delete() }
                404 -> { dir.mkdirs(); miss.writeText("") }
            }
        }
        if (!f.exists()) return emptyList()
        return runCatching { parseRta(JSONObject(f.readText()), content) }.getOrDefault(emptyList())
    }

    fun parseRta(j: JSONObject, content: Content): List<Preset> {
        val builds = j.getJSONObject("0").getJSONObject("a").getJSONArray("builds")
        return (0 until builds.length()).map { i ->
            val b = builds.getJSONObject(i)
            val st = b.getJSONObject("stats")
            val sets = HashMap<String, Int>()
            b.optJSONArray("sets")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { s ->
                // the file lists pieces worn ("Unity 1, Reversal 1, Speed 4"); only complete sets count
                content.setByName(s.getString("name"))?.let { d ->
                    val n = s.getInt("pieces")
                    if (n >= d.pieces) sets[d.key] = (n / d.pieces) * d.pieces
                }
            } }
            Preset("RTA #${b.optInt("sample", i + 1)}", Math.round(100 * b.optDouble("weight", 0.125)).toInt(), sets,
                b.optStringOrNull("artifact"),
                doubleArrayOf(st.optDouble("att"), st.optDouble("max_hp"), st.optDouble("def"), st.optDouble("speed"),
                    100 * st.optDouble("cri"), 100 * st.optDouble("cri_dmg"), 100 * st.optDouble("acc"), 100 * st.optDouble("res")),
                rta = true)
        }
    }

    private fun fetch(url: String, name: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(name.toByteArray()) }
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private class Row(val stats: DoubleArray, val gs: Double, val sets: Map<String, Int>, val artifact: String?)

    fun parse(j: JSONObject, content: Content): PresetTabs {
        val a = j.getJSONArray("data")
        var date: String? = null
        val rows = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            o.optString("createDate").takeIf { it.isNotEmpty() }?.let { if (date == null || it > date!!) date = it }
            val so = o.optJSONObject("sets")
            val sets = HashMap<String, Int>()
            so?.keys()?.forEach { k ->
                val key = content.setFromGame(k) ?: return@forEach
                val n = so.optString(k).toIntOrNull() ?: 0
                val def = content.set(key) ?: return@forEach
                if (n >= def.pieces) sets[key] = (n / def.pieces) * def.pieces
            }
            Row(doubleArrayOf(o.optDouble("atk"), o.optDouble("hp"), o.optDouble("def"), o.optDouble("spd"),
                o.optDouble("chc"), o.optDouble("chd"), o.optDouble("eff"), o.optDouble("efr")),
                o.optDouble("gs", 0.0), sets, o.optStringOrNull("artifactCode"))
        }
        fun clusters(rs: List<Row>): List<Preset> {
            val groups = rs.groupBy { r -> r.sets.toSortedMap().toString() }.values.sortedByDescending { it.size }
            return groups.take(8).mapIndexed { i, g ->
                Preset("Community #${i + 1}", Math.round(100.0 * g.size / rs.size).toInt(), g.first().sets,
                    g.mapNotNull { it.artifact }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    DoubleArray(8) { x -> median(g.map { it.stats[x] }) })
            }
        }
        val n = rows.size
        val topGs = rows.sortedByDescending { it.gs }.take(maxOf(1, n / 4))
        val fastest = rows.sortedByDescending { it.stats[3] }.take(maxOf(1, n / 4))
        return PresetTabs(clusters(rows), clusters(topGs), clusters(fastest), n, date)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * A request from a preset: its sets and artifact; a target on every stat the build invests in
     * (median minus gearless stats, in rolls), weighted by how much; accessory mains that serve those stats.
     */
    fun toRequest(p: Preset, hb: HeroBase, keep: Request?, tab: String = "Most used", auto: Boolean = false): Request {
        val r = keep?.copy() ?: Request()
        r.presetLabel = "$tab · ${p.label}"
        r.presetAuto = auto
        r.presetShare = p.share
        r.artifact = p.artifact
        r.setAlts = if (p.sets.isEmpty()) emptyList() else listOf(p.sets)
        val roll = Objectives.rollSize(hb)
        // every build carries these weapon, helmet and armor mains, so they are not investment
        val floor = hb.fixed.copyOf()
        floor[0] += 525 * hb.mult[0]; floor[1] += 2835 * hb.mult[1]; floor[2] += 310 * hb.mult[2]
        for (x in 0 until 8) {
            r.targets[x] = Double.NaN; r.weights[x] = 0; r.fromPreset[x] = false; r.mins[x] = Double.NaN
            val m = p.median[x]
            if (m.isNaN()) continue
            val rolls = (m - floor[x]) / roll[x]
            if (rolls < 4) continue
            r.targets[x] = if (x == Stat.CR.ordinal) minOf(m, 100.0) else Math.round(m).toDouble()
            r.weights[x] = when { rolls >= 12 -> 3; rolls >= 7 -> 2; else -> 1 }
            r.fromPreset[x] = true
        }
        r.mains = Slot.ACCESSORIES.associateWith { slot ->
            val opts = Ghost.ACCESSORY_MAINS.getValue(slot).filter { it.percentOfBase || it.stat.pct || it == GearStat.SPD }
            val best = opts.maxOf { r.weights[it.stat.ordinal] }
            if (best < 2) emptySet() else opts.filter { r.weights[it.stat.ordinal] == best }.take(2).toSet()
        }.filterValues { it.isNotEmpty() }
        r.goal = Goal.TARGETS
        return r
    }
}
