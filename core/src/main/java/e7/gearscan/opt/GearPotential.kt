package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*
import e7.gearscan.value.Chance
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Joint minimum-substat probability at +15. Enumerates shared roll allocations, not Monte Carlo samples. */
object GearPotential {
    data class Goal(
        val minimums: Map<GearStat, Double>,
        val reforge: Boolean = true,
        val reforgeAllowed: Boolean = true,
    ) {
        init {
            require(minimums.isNotEmpty() && minimums.size <= 4 && minimums.values.all { it.isFinite() && it > 0 })
        }

        fun toJson() =
            JSONObject()
                .put("stats", JSONObject().apply { minimums.forEach { (s, v) -> put(s.key, v) } })
                .put("reforge", reforge)
                .put("reforgeAllowed", reforgeAllowed)

        fun label() = minimums.entries.joinToString(" · ") { (s, v) -> "${if (s.percentOfBase) s.stat.short else s.short} ${s.format(v)}+" }

        companion object {
            fun of(j: JSONObject): Goal {
                val stats = j.getJSONObject("stats")
                return Goal(
                    stats
                        .keys()
                        .asSequence()
                        .mapNotNull { k -> GearStat.of(k)?.let { it to stats.getDouble(k) } }
                        .toMap(),
                    j.optBoolean("reforge", true),
                    j.optBoolean("reforgeAllowed", true),
                )
            }
        }
    }

    data class Outcome(
        val chance: Double,
        val possible: Boolean,
        val supported: Boolean,
        val matchesNow: Boolean,
    ) {
        val rareOnly get() = supported && possible && chance == 0.0

        /** Enhancing on can still pay: reachable with usual rolls and not there yet. */
        val keepRolling get() = supported && possible && !matchesNow && chance > 0.0

        /** The one short word every screen shows for this outcome. */
        fun word() =
            when {
                !supported -> "?"
                matchesNow -> "Ready"
                rareOnly -> "Rare"
                !possible -> "0%"
                else -> Chance(chance).text
            }

        fun label() =
            when {
                !supported -> "Not forecast"
                rareOnly -> "Rare rolls needed"
                !possible -> "Cannot reach targets"
                else -> Chance(chance).text
            }
    }

    private data class SumKey(
        val stat: GearStat,
        val level: Int,
        val rolls: Int,
    )

    private val tails = ConcurrentHashMap<SumKey, DoubleArray>()

    private fun tail(
        stat: GearStat,
        level: Int,
        rolls: Int,
    ): DoubleArray =
        tails.getOrPut(SumKey(stat, level, rolls)) {
            val range = Finish.range(stat, level)
            var values = doubleArrayOf(1.0)
            repeat(rolls) {
                val next = DoubleArray(values.size + range.last)
                for (i in values.indices) if (values[i] > 0) for (v in range) next[i + v] += values[i] / range.count()
                values = next
            }
            for (i in values.lastIndex - 1 downTo 0) values[i] += values[i + 1]
            values
        }

    fun forecast(
        item: Item,
        goal: Goal,
    ): Outcome {
        val types = goal.minimums.keys.toList()
        val lines = types.map { t -> item.subs.firstOrNull { it.type == t } }
        val now = types.indices.all { i -> (lines[i]?.value ?: 0.0) >= goal.minimums.getValue(types[i]) }
        if (now) return Outcome(1.0, true, true, true)
        val ref = goal.reforge && goal.reforgeAllowed && item.level == 85
        if (item.enhance == 15) {
            val ok =
                types.indices.all { i ->
                    lines[i]?.let { s ->
                        s.value + (if (ref) Reforge.gain(s.type, s.rolls) else 0.0) >=
                            goal.minimums.getValue(types[i])
                    } ==
                        true
                }
            return Outcome(if (ok) 1.0 else 0.0, ok, true, now)
        }
        if (item.level !in listOf(85, 88) ||
            item.rank !in listOf("Epic", "Heroic") ||
            item.enhance !in 0..14 ||
            item.subs.size != (if (item.rank == "Heroic" && item.enhance < 12) 3 else 4) ||
            item.subs
                .map { it.type }
                .distinct()
                .size != item.subs.size ||
            item.subs.any { it.type == item.mainType || !it.value.isFinite() || it.value <= 0 }
        ) {
            return Outcome(0.0, false, false, now)
        }
        val missing = types.indices.filter { lines[it] == null }
        val reveal = item.rank == "Heroic" && item.enhance < 12 && item.subs.size == 3
        if (missing.size > 1 || missing.isNotEmpty() && (!reveal || types[missing.single()] !in UpgradeRecord.allowedNew(item))) {
            return Outcome(0.0, false, true, now)
        }
        val missingIndex = missing.firstOrNull()
        val first = (item.enhance / 3 + 1) * 3
        // Three bits per requested stat store how many new rolls it receives (0..6), including a reveal.
        var allocations = mapOf(0 to 1.0)
        for (e in first..15 step 3) {
            val next = HashMap<Int, Double>()
            if (reveal && e == 12) {
                val weight = if (missingIndex == null) 1.0 else 1.0 / UpgradeRecord.allowedNew(item).size
                for ((key, p) in allocations) next[key + (missingIndex?.let { 1 shl (it * 3) } ?: 0)] = p * weight
            } else {
                val count = if (item.rank == "Heroic" && e < 12) 3 else 4
                val active = types.indices.filter { lines[it] != null || missingIndex == it && e > 12 }
                for ((key, p) in allocations) {
                    val other = count - active.size
                    if (other > 0) next[key] = (next[key] ?: 0.0) + p * other / count
                    for (i in active) {
                        val k = key + (1 shl (i * 3))
                        next[k] = (next[k] ?: 0.0) + p / count
                    }
                }
            }
            allocations = next
        }
        var probability = 0.0
        var possible = false
        for ((allocation, weight) in allocations) {
            var p = weight
            var feasible = true
            for (i in types.indices) {
                val t = types[i]
                val n = (allocation shr (3 * i)) and 7
                val s = lines[i]
                val bonus = if (ref) Reforge.gain(t, (s?.rolls ?: 0) + n) else 0.0
                val needed =
                    kotlin.math
                        .ceil(goal.minimums.getValue(t) - (s?.value ?: 0.0) - bonus - 1e-9)
                        .toInt()
                        .coerceAtLeast(0)
                if (s == null && n == 0 || needed > n * Finish.maximumRoll(t, item.level)) feasible = false
                val sums = tail(t, item.level, n)
                p *= if (needed < sums.size) sums[needed] else 0.0
            }
            probability += p
            possible = possible || feasible
        }
        return Outcome(probability.coerceIn(0.0, 1.0), possible, true, now)
    }
}
