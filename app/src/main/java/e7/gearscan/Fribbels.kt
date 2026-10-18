package e7.gearscan

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Decoding happens on Fribbels' server, exactly as in the Fribbels optimizer's auto-importer
 * (app/js/lib/scanner.js); convert/convertItem mirror its finishedReading/convertItems.
 */
object Fribbels {
    const val API = "https://krivpfvxi0.execute-api.us-west-2.amazonaws.com/dev/getItems"

    class Result(val items: JSONArray, val heroes: JSONArray)

    fun getItems(segments: List<String>): JSONObject {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 30000
        c.readTimeout = 180000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.bufferedWriter().use { w ->
            w.write("{\"data\":[")
            segments.forEachIndexed { i, s ->
                if (i > 0) w.write(",")
                w.write("\"$s\"")
            }
            w.write("]}")
        }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code != 200) throw IOException("Fribbels API HTTP $code: ${body.take(300)}")
        return JSONObject(body)
    }

    fun convert(resp: JSONObject): Result {
        if (resp.optString("status") != "SUCCESS")
            throw JSONException("Fribbels API status ${resp.optString("status")}")
        val items = JSONArray()
        resp.optJSONArray("data")?.objects()
            ?.filter { it.has("f") && !it.isNull("f") && it.opt("f") != "" }
            ?.forEach { if (convertItem(it)) items.put(it) }

        val units = resp.optJSONArray("units")?.let { lists ->
            (0 until lists.length()).map { lists.getJSONArray(it) }.maxByOrNull { it.length() }
        } ?: JSONArray()
        val heroes = JSONArray()
        units.objects().filter { it.optString("name").isNotEmpty() }.forEach { u ->
            if (u.has("id")) {
                u.put("stars", if (u.has("g")) u.get("g") else JSONObject.NULL)
                u.put("awaken", if (u.has("z")) u.get("z") else JSONObject.NULL)
            }
            heroes.put(u)
        }
        return Result(items, heroes)
    }

    private val STAT = mapOf(
        "att_rate" to "AttackPercent", "max_hp_rate" to "HealthPercent", "def_rate" to "DefensePercent",
        "att" to "Attack", "max_hp" to "Health", "def" to "Defense", "speed" to "Speed",
        "res" to "EffectResistancePercent", "cri" to "CriticalHitChancePercent",
        "cri_dmg" to "CriticalHitDamagePercent", "acc" to "EffectivenessPercent",
        "coop" to "DualAttackChancePercent")
    private val RANK = listOf("Unknown", "Normal", "Good", "Rare", "Heroic", "Epic")
    private val COUNT = mapOf("Normal" to 5, "Good" to 6, "Rare" to 7, "Heroic" to 8, "Epic" to 9)
    private val OFFSET = mapOf("Normal" to 0, "Good" to 1, "Rare" to 2, "Heroic" to 3, "Epic" to 4)
    private val GEAR_BY_TYPE = mapOf(
        "weapon" to "Weapon", "helm" to "Helmet", "armor" to "Armor",
        "neck" to "Necklace", "ring" to "Ring", "boot" to "Boots")
    private val GEAR_BY_LETTER = mapOf(
        "w" to "Weapon", "h" to "Helmet", "a" to "Armor", "n" to "Necklace", "r" to "Ring", "b" to "Boots")
    private val SET = mapOf(
        "set_acc" to "HitSet", "set_att" to "AttackSet", "set_coop" to "UnitySet", "set_counter" to "CounterSet",
        "set_cri_dmg" to "DestructionSet", "set_cri" to "CriticalSet", "set_def" to "DefenseSet",
        "set_immune" to "ImmunitySet", "set_max_hp" to "HealthSet", "set_penetrate" to "PenetrationSet",
        "set_rage" to "RageSet", "set_res" to "ResistSet", "set_revenge" to "RevengeSet", "set_scar" to "InjurySet",
        "set_speed" to "SpeedSet", "set_vampire" to "LifestealSet", "set_shield" to "ProtectionSet",
        "set_torrent" to "TorrentSet", "set_revenant" to "ReversalSet", "set_riposte" to "RiposteSet",
        "set_chase" to "PursuitSet", "set_opener" to "WarfareSet", "set_weak" to "WeakeningSet",
        "set_might" to "FervorSet")
    private val FLAT = setOf("max_hp", "speed", "att", "def")

    private fun round10ths(x: Double) = (x * 10).roundToLong() / 10.0

    /** Adds the optimizer fields to a raw item in place; false if the item can't be read. */
    fun convertItem(it: JSONObject): Boolean {
        val type = it.optString("type")
        if (type.isEmpty()) {
            val base = it.optString("code").split("_")[0]
            it.put("gear", if (base.isEmpty()) JSONObject.NULL else GEAR_BY_LETTER[base.takeLast(1)])
        } else {
            it.put("gear", GEAR_BY_TYPE[type])
        }
        val g = it.optInt("g", 0)
        if (g <= 0 || g >= RANK.size) return false
        val rank = RANK[g]
        it.put("rank", rank)
        it.put("set", SET[it.optString("f")])
        if (!it.has("name")) it.put("name", "Unknown")
        it.put("level", it.optInt("level", 0))

        val subs = it.getJSONArray("op")
        val count = min(subs.length() - 1, COUNT.getValue(rank))
        it.put("enhance", max((count - OFFSET.getValue(rank)) * 3, 0))

        val mainRaw = subs.getJSONArray(0).getString(0)
        val mv = it.optDouble("mainStatValue", Double.NaN)
        val mainValue = (if (mainRaw in FLAT) mv else round10ths(mv * 100)).let { v -> if (v.isNaN()) 0.0 else v }
        it.put("main", stat(STAT[mainRaw], mainValue, null, false))

        val acc = linkedMapOf<String?, DoubleArray>()
        val modified = mutableSetOf<String?>()
        for (i in 1 until subs.length()) {
            val op = subs.getJSONArray(i)
            val opType = op.getString(0)
            val annotation = if (op.length() > 2) op.optString(2) else ""
            val t = STAT[opType]
            val v = if (opType in FLAT) op.getDouble(1) else round10ths(op.getDouble(1) * 100)
            val a = acc[t]
            if (a == null) {
                acc[t] = doubleArrayOf(v, 1.0)
            } else {
                a[0] += v
                when (annotation) {
                    "c" -> modified += t
                    "u" -> {}
                    else -> a[1] += 1
                }
            }
        }
        it.put("substats", JSONArray(acc.map { (t, a) -> stat(t, a[0], a[1].toInt(), t in modified) }))
        it.put("ingameId", it.opt("id"))
        it.put("ingameEquippedId", if (it.has("p") && !it.isNull("p")) it.get("p").toString() else "None")
        return true
    }

    private fun stat(type: String?, value: Double, rolls: Int?, modified: Boolean) = JSONObject().apply {
        put("type", type)
        put("value", value)
        if (rolls != null) put("rolls", rolls)
        if (modified) put("modified", true)
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
