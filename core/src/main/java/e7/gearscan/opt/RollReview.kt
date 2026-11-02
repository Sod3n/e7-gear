package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Raw upgrade evidence and single-stat possibilities; no stat-value weights. */
object RollReview {
    const val VERSION = "overall-roll-review-v3"
    const val BASIS = "ALL_ROLLS"

    data class Roll(
        val stat: GearStat,
        val value: Double,
        val level: Int,
        val enhance: Int = 0,
        val reveal: Boolean = false,
    ) {
        val upgrade get() = enhance > 0 && !reveal
        val range get() = Finish.range(stat, level)
        val fraction get() = (value - range.first) / (range.last - range.first)

        fun toJson() =
            JSONObject()
                .put("stat", stat.key)
                .put("value", value)
                .put("level", level)
                .put("enhance", enhance)
                .put("reveal", reveal)

        companion object {
            fun of(j: JSONObject) =
                Roll(
                    GearStat.of(j.getString("stat"))!!,
                    j.getDouble("value"),
                    j.getInt("level"),
                    j.getInt("enhance"),
                    j.optBoolean("reveal"),
                )
        }
    }

    fun rollsJson(rolls: List<Roll>?) = rolls?.let { JSONArray(it.map(Roll::toJson)) } ?: JSONObject.NULL

    fun rollsOf(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> Roll.of(it.getJSONObject(i)) } }

    fun append(
        rolls: List<Roll>?,
        before: Item,
        after: Item,
    ): List<Roll>? {
        if (rolls == null || after.enhance != before.enhance + 3)return null
        val added = after.subs.firstOrNull { a -> before.subs.none { it.type == a.type } }
        val changes =
            after.subs.mapNotNull { a ->
                before.subs.firstOrNull { it.type == a.type }?.let { b ->
                    (a.value - b.value).takeIf { it > 0 }?.let {
                        a.type to
                            it
                    }
                }
            }
        val pair = if (added != null && changes.isEmpty())added.type to added.value else changes.singleOrNull() ?: return null
        val level = if (after.level == 90)85 else after.level
        if (pair.second < Finish.range(pair.first, level).first || pair.second > Finish.maximumRoll(pair.first, level))return null
        return rolls + Roll(pair.first, pair.second, level, after.enhance, added != null)
    }

    fun upgrades(rolls: List<Roll>?) = rolls?.filter { it.upgrade }

    fun overallWeakCount(rolls: List<Roll>?) = rolls?.count { it.fraction < .5 }

    fun overallMinimumCount(rolls: List<Roll>?) = rolls?.count { it.value == it.range.first.toDouble() }

    fun weakCount(rolls: List<Roll>?) = upgrades(rolls)?.count { it.fraction < .5 }

    fun minimumCount(rolls: List<Roll>?) = upgrades(rolls)?.count { it.value == it.range.first.toDouble() }

    fun focus(rolls: List<Roll>?) =
        upgrades(rolls)
            ?.groupingBy { it.stat }
            ?.eachCount()
            ?.entries
            ?.sortedWith(
                compareByDescending<Map.Entry<GearStat, Int>> { it.value }.thenBy {
                    if (it.key ==
                        GearStat.SPD
                    ) {
                        0
                    } else {
                        1
                    }
                },
            )?.firstOrNull()

    fun expectedProcs(item: Item) = item.enhance / 3 - if (item.rank == "Heroic" && item.enhance >= 12)1 else 0

    data class Line(
        val stat: GearStat,
        val current: Double,
        val minimum: Double?,
        val average: Double?,
        val maximum: Double?,
        val rare: Double?,
    ) {
        fun toJson() =
            JSONObject()
                .put("stat", stat.key)
                .put("current", current)
                .put("minimum", minimum ?: JSONObject.NULL)
                .put(
                    "average",
                    average ?: JSONObject.NULL,
                ).put("maximum", maximum ?: JSONObject.NULL)
                .put("rare", rare ?: JSONObject.NULL)

        companion object {
            fun of(j: JSONObject): Line {
                fun n(k: String): Double? = if (j.isNull(k))null else j.getDouble(k)
                return Line(GearStat.of(j.getString("stat"))!!, j.getDouble("current"), n("minimum"), n("average"), n("maximum"), n("rare"))
            }
        }
    }

    /** Exact marginal allocation of a line, including an optional Heroic reveal and shared upgrade slots. */
    fun line(
        item: Item,
        stat: GearStat,
        reforge: Boolean,
        allowed: Boolean,
        coverage: Boolean,
    ): Line {
        val sub = item.subs.firstOrNull { it.type == stat }
        val value = sub?.value ?: 0.0

        fun unknown() = Line(stat, value, null, null, null, null)
        if (item.enhance < 15 && !coverage)return unknown()
        if (!GearPotential.forecast(item, GearPotential.Goal(mapOf(stat to 1e9), reforge, allowed)).supported)return unknown()
        val ref = reforge && allowed && item.level == 85
        // -1 denotes a line which is still absent.
        var states = mapOf((if (sub == null)-1 else 0) to 1.0)
        for (e in ((item.enhance / 3 + 1) * 3)..15 step 3) {
            val next = HashMap<Int, Double>()

            fun add(
                n: Int,
                p: Double,
            ) {
                next[n] = (next[n] ?: 0.0) + p
            }
            val reveal = item.rank == "Heroic" && item.enhance < 12 && e == 12
            for ((n, p) in states) {
                when {
                    reveal && n < 0 -> {
                        val pool = UpgradeRecord.allowedNew(item)
                        val chance = if (stat in pool)1.0 / pool.size else 0.0
                        if (chance > 0)add(1, p * chance)
                        if (chance < 1)add(-1, p * (1 - chance))
                    }
                    reveal || n < 0 -> add(n, p)
                    else -> {
                        val count = if (item.rank == "Heroic" && e < 12)3 else 4
                        add(n + 1, p / count)
                        add(n, p * (count - 1) / count)
                    }
                }
            }
            states = next
        }
        val range = Finish.range(stat, item.level)
        var low = Double.POSITIVE_INFINITY
        var high = 0.0
        var rare = 0.0
        var mean = 0.0
        for ((n, p) in states) {
            if (p > 0) {
                val bonus = if (n >= 0 && ref)Reforge.gain(stat, (sub?.rolls ?: 0) + n) else 0.0
                val a = if (n < 0)0.0 else value + n * range.first + bonus
                val b = if (n < 0)0.0 else value + n * range.last + bonus
                val r = if (n < 0)0.0 else value + n * Finish.maximumRoll(stat, item.level) + bonus
                low = minOf(low, a)
                high = maxOf(high, b)
                rare = maxOf(rare, r)
                mean += p * (a + b) / 2
            }
        }
        return Line(stat, value, low, mean, high, rare)
    }

    data class Safeguard(
        val stat: GearStat,
        val minimum: Double,
        val chance: Double,
        val possible: Boolean,
        val known: Boolean,
        val now: Boolean,
    ) {
        fun active(limit: Double) = now || known && (if (limit == 0.0)possible else chance * 100 >= limit - 1e-9)

        fun toJson() =
            JSONObject()
                .put(
                    "stat",
                    stat.key,
                ).put("minimum", minimum)
                .put("chance", chance)
                .put("possible", possible)
                .put("known", known)
                .put("now", now)

        companion object {
            fun of(j: JSONObject) =
                Safeguard(
                    GearStat.of(j.getString("stat"))!!,
                    j.getDouble("minimum"),
                    j.getDouble("chance"),
                    j.getBoolean("possible"),
                    j.getBoolean("known"),
                    j.getBoolean("now"),
                )
        }
    }

    fun safeguard(
        item: Item,
        stat: GearStat,
        minimum: Double,
        reforge: Boolean,
        allowed: Boolean,
        coverage: Boolean,
    ): Safeguard {
        val now = item.subs.any { it.type == stat && it.value >= minimum }
        if (!now && item.enhance < 15 && !coverage)return Safeguard(stat, minimum, 0.0, false, false, false)
        val out = GearPotential.forecast(item, GearPotential.Goal(mapOf(stat to minimum), reforge, allowed))
        return Safeguard(stat, minimum, out.chance, out.possible, out.supported, now)
    }
}
