package e7.gearscan.data

import org.json.JSONArray
import org.json.JSONObject

enum class Goal(val label: String, val short: String) {
    TARGETS("closest to targets", "Targets"),
    DAMAGE("max damage", "Damage"),
    EHP("max EHP", "EHP"),
    SPEED("max speed", "Speed"),
    CP("max CP", "CP"),
    GEAR_SCORE("max gear score", "Gear score"),
}

/** Extra bonus stats on top of base, imprint, EE and artifact. */
enum class Bonus(val label: String) {
    ATK_P("ATK %"), HP_P("HP %"), DEF_P("DEF %"), SPD("SPD"), CR("CR"), CD("CD"),
    ATK("ATK"), HP("HP"), DEF("DEF"), EFF("EFF"), RES("RES"),
    FINAL_ATK("Final ATK %"), FINAL_HP("Final HP %"), FINAL_DEF("Final DEF %");
}

class SavedBuild(val name: String, val items: List<Long>)

class HeroConfig {
    var artifact: String? = null
    var stars = 6
    var eeValue: Double? = null
    val bonus = DoubleArray(Bonus.entries.size)
    var request: Request? = null
    val saved = mutableListOf<SavedBuild>()

    fun toJson(): JSONObject = JSONObject().apply {
        put("artifact", artifact ?: JSONObject.NULL)
        put("stars", stars)
        put("ee", eeValue ?: JSONObject.NULL)
        put("bonus", JSONArray(bonus.toList()))
        request?.let { put("request", it.toJson()) }
        put("saved", JSONArray(saved.map { JSONObject().put("name", it.name).put("items", JSONArray(it.items)) }))
    }

    companion object {
        fun fromJson(o: JSONObject) = HeroConfig().apply {
            artifact = o.optStringOrNull("artifact")
            stars = o.optInt("stars", 6)
            eeValue = if (o.isNull("ee")) null else o.optDouble("ee")
            o.optJSONArray("bonus")?.let { a -> for (i in 0 until minOf(a.length(), bonus.size)) bonus[i] = a.getDouble(i) }
            request = o.optJSONObject("request")?.let(Request::fromJson)
            o.optJSONArray("saved")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val ids = s.getJSONArray("items")
                    saved += SavedBuild(s.getString("name"), (0 until ids.length()).map(ids::getLong))
                }
            }
        }
    }
}

/** One hero's optimizer setup. Arrays are indexed by Stat.ordinal; NaN = not set. */
class Request {
    var presetLabel: String? = null
    var presetShare: Int = 0
    var presetAuto = false
    /** Looked over in the pool walk-through (Next pressed), so an interrupted walk resumes where it stopped. */
    var reviewed = false
    var artifact: String? = null
    var setAlts: List<Map<String, Int>> = emptyList()
    var mains: Map<Slot, Set<GearStat>> = emptyMap()
    val mins = DoubleArray(8) { Double.NaN }
    val maxs = DoubleArray(8) { Double.NaN }
    val targets = DoubleArray(8) { Double.NaN }
    val weights = IntArray(8)
    val fromPreset = BooleanArray(8)
    var goal = Goal.TARGETS
    var minEnhance = 12
    var lowerPriorityOnly = true
    var allowLocked = true
    var keepCurrent = false
    var reforged = true
    var ghost = true
    var ghostQuality = 1
    var ghostSlots: Set<Slot> = Slot.entries.toSet()
    /** Substat mods: let the search change one weak substat per +15 item with a modification stone. */
    var mods = false
    /** Crit chance over 100% does nothing, so builds wasting more than a roll of it are left out unless a max is set by hand. */
    var capCrit = true
    var rageDamage = false
    var fewerSwaps = true
    /** Skill the damage goal hits with; 0 picks the hardest-hitting one. */
    var skill = 0
    /** At most this many pieces differ from the hero's current gear (6 = any). */
    var maxChanges = 6
    var modGreater = true
    var modQuality = 1
    var modMaxRolls = 2

    fun copy(): Request = fromJson(toJson())

    /** Nothing chosen yet, so a preset may fill it in. */
    fun isBlank() = presetLabel == null && setAlts.isEmpty() && mains.isEmpty() && goal == Goal.TARGETS &&
        targets.all { it.isNaN() } && mins.all { it.isNaN() } && maxs.all { it.isNaN() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("presetLabel", presetLabel ?: JSONObject.NULL)
        put("presetShare", presetShare)
        put("presetAuto", presetAuto)
        put("reviewed", reviewed)
        put("artifact", artifact ?: JSONObject.NULL)
        put("setAlts", JSONArray(setAlts.map { JSONObject(it) }))
        put("mains", JSONObject().apply { mains.forEach { (s, v) -> put(s.key, JSONArray(v.map { it.key })) } })
        put("mins", nanArray(mins)); put("maxs", nanArray(maxs)); put("targets", nanArray(targets))
        put("weights", JSONArray(weights.toList()))
        put("fromPreset", JSONArray(fromPreset.toList()))
        put("goal", goal.name)
        put("minEnhance", minEnhance)
        put("lowerPriorityOnly", lowerPriorityOnly)
        put("allowLocked", allowLocked)
        put("keepCurrent", keepCurrent)
        put("reforged", reforged)
        put("ghost", ghost)
        put("ghostQuality", ghostQuality)
        put("capCrit", capCrit); put("rageDamage", rageDamage); put("fewerSwaps", fewerSwaps); put("skill", skill); put("maxChanges", maxChanges); put("mods", mods); put("modGreater", modGreater); put("modQuality", modQuality); put("modMaxRolls", modMaxRolls)
        put("ghostSlots", JSONArray(ghostSlots.map { it.key }))
    }

    companion object {
        private fun nanArray(a: DoubleArray) = JSONArray(a.map { if (it.isNaN()) JSONObject.NULL else it })
        private fun readNan(a: JSONArray?, into: DoubleArray) {
            if (a == null) return
            for (i in 0 until minOf(a.length(), into.size)) into[i] = if (a.isNull(i)) Double.NaN else a.getDouble(i)
        }

        fun fromJson(o: JSONObject) = Request().apply {
            presetLabel = o.optStringOrNull("presetLabel")
            presetShare = o.optInt("presetShare")
            presetAuto = o.optBoolean("presetAuto")
            reviewed = o.optBoolean("reviewed")
            artifact = o.optStringOrNull("artifact")
            o.optJSONArray("setAlts")?.let { a ->
                setAlts = (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    m.keys().asSequence().associateWith { m.getInt(it) }
                }
            }
            o.optJSONObject("mains")?.let { m ->
                mains = m.keys().asSequence().mapNotNull { k ->
                    val arr = m.getJSONArray(k)
                    Slot.of(k)?.let { it to (0 until arr.length()).mapNotNull { i -> GearStat.of(arr.getString(i)) }.toSet() }
                }.toMap()
            }
            readNan(o.optJSONArray("mins"), mins); readNan(o.optJSONArray("maxs"), maxs)
            readNan(o.optJSONArray("targets"), targets)
            o.optJSONArray("weights")?.let { a -> for (i in 0 until minOf(8, a.length())) weights[i] = a.getInt(i) }
            o.optJSONArray("fromPreset")?.let { a -> for (i in 0 until minOf(8, a.length())) fromPreset[i] = a.getBoolean(i) }
            goal = runCatching { Goal.valueOf(o.optString("goal")) }.getOrDefault(Goal.TARGETS)
            minEnhance = o.optInt("minEnhance", 12)
            lowerPriorityOnly = o.optBoolean("lowerPriorityOnly", true)
            allowLocked = o.optBoolean("allowLocked", true)
            keepCurrent = o.optBoolean("keepCurrent", false)
            reforged = o.optBoolean("reforged", true)
            ghost = o.optBoolean("ghost", true)
            ghostQuality = o.optInt("ghostQuality", 1)
            capCrit = o.optBoolean("capCrit", true); rageDamage = o.optBoolean("rageDamage", false); fewerSwaps = o.optBoolean("fewerSwaps", true); skill = o.optInt("skill", 0); maxChanges = o.optInt("maxChanges", 6); mods = o.optBoolean("mods", false); modGreater = o.optBoolean("modGreater", true)
            modQuality = o.optInt("modQuality", 1); modMaxRolls = o.optInt("modMaxRolls", 2)
            o.optJSONArray("ghostSlots")?.let { a -> ghostSlots = (0 until a.length()).mapNotNull { Slot.of(a.getString(it)) }.toSet() }
        }
    }
}
