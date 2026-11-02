package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Full main+substat budget, with an optional discount outside the largest substat dimension. */
object GearScore {
    const val VERSION = "score-floor-v1"
    private const val SCALE = 100_000_000.0
    val weights =
        GearStat.entries.associateWith {
            when (it) {
                GearStat.SPD -> 2.0
                GearStat.CR -> 1.6
                GearStat.CD -> 1.14
                GearStat.ATK -> .0972
                GearStat.HP -> .02
                GearStat.DEF -> .135
                else -> 1.0
            }
        }

    data class Breakdown(
        val main: Double,
        val groups: Map<Stat, Double>,
        val diversity: Int,
    ) {
        val substats get() = groups.values.sum()
        val discount get() = diversity / 100.0 * (substats - (groups.values.maxOrNull() ?: 0.0))
        val total get() = main + (1 - diversity / 100.0) * substats + diversity / 100.0 * (groups.values.maxOrNull() ?: 0.0)
    }

    data class Model(
        val weights: Map<GearStat, Double> = GearScore.weights,
        val mainPercent: Int = 100,
        val diversity: Int = 0,
    ) {
        fun weight(s: GearStat) = weights[s] ?: GearScore.weights.getValue(s)

        fun valid() =
            mainPercent in 0..500 && diversity in 0..100 && GearStat.entries.all { weight(it).isFinite() && weight(it) in 0.0..10.0 }

        fun value(item: Item) = breakdown(item).total

        fun breakdown(item: Item): Breakdown {
            val current = if (item.enhance < 15)UpgradeRecord.fromScan(item) else item
            val groups = current.subs.groupBy { it.type.stat }.mapValues { (_, ss) -> ss.sumOf { it.value * weight(it.type) } }
            return Breakdown(current.mainValue * weight(current.mainType) * mainPercent / 100, groups, diversity)
        }

        fun toJson() =
            JSONObject()
                .put(
                    "weights",
                    JSONObject().apply {
                        GearStat.entries.forEach { put(it.key, weight(it)) }
                    },
                ).put("mainPercent", mainPercent)
                .put("diversity", diversity)

        companion object {
            fun of(j: JSONObject): Model {
                val w = j.optJSONObject("weights")
                return Model(
                    GearStat.entries.associateWith {
                        w?.optDouble(it.key, GearScore.weights.getValue(it))
                            ?: GearScore.weights.getValue(it)
                    },
                    j.optInt("mainPercent", 100),
                    j.optInt("diversity", 0),
                ).also { require(it.valid()) }
            }
        }
    }

    data class Goal(
        val model: Model,
        val floor: Double?,
        val reforge: Boolean,
        val reforgeAllowed: Boolean,
        val supported: Boolean = true,
        val reference: String = "Applied floor",
        val budgetMs: Int = 250,
        val referenceItemId: Long? = null,
    ) {
        fun label() = floor?.let { "Reach ${fmtNum(it)} pts" } ?: "No comparison piece"

        fun referenceLabel() =
            when (reference) {
                "Custom floor" -> "Your custom score"
                "No reference floor" -> "No comparison piece"
                "Applied floor" -> "Applied comparison"
                else -> reference
            }

        fun toJson() =
            JSONObject()
                .put(
                    "model",
                    model.toJson(),
                ).put("floor", floor ?: JSONObject.NULL)
                .put("reforge", reforge)
                .put("reforgeAllowed", reforgeAllowed)
                .put("supported", supported)
                .put("reference", reference)
                .put("budgetMs", budgetMs)
                .put(
                    "referenceItemId",
                    referenceItemId ?: JSONObject.NULL,
                )

        companion object {
            fun of(j: JSONObject) =
                Goal(
                    Model.of(j.getJSONObject("model")),
                    if (j.isNull("floor"))null else j.getDouble("floor"),
                    j.getBoolean("reforge"),
                    j.getBoolean("reforgeAllowed"),
                    j.optBoolean("supported", true),
                    j.optString("reference", "Applied floor"),
                    j.optInt("budgetMs", 250).coerceIn(25, 5000),
                    if (j.isNull("referenceItemId"))null else j.getLong("referenceItemId"),
                )
        }
    }

    data class Forecast(
        val chance: Double,
        val possible: Boolean,
        val supported: Boolean,
        val current: Double,
        val expected: Double?,
        val best: Double?,
        val rareBest: Double?,
        val reason: String? = null,
    ) {
        val rareOnly get() = supported && possible && chance == 0.0

        fun outcome(floor: Double?) = GearPotential.Outcome(chance, possible, supported, floor?.let { current >= it - 1e-8 } == true)

        fun toJson() =
            JSONObject()
                .put("chance", chance)
                .put("possible", possible)
                .put("supported", supported)
                .put("current", current)
                .put(
                    "expected",
                    expected ?: JSONObject.NULL,
                ).put("best", best ?: JSONObject.NULL)
                .put("rareBest", rareBest ?: JSONObject.NULL)
                .put(
                    "reason",
                    reason ?: JSONObject.NULL,
                )

        companion object {
            fun of(j: JSONObject): Forecast {
                fun optional(k: String): Double? = if (j.isNull(k))null else j.getDouble(k)
                return Forecast(
                    j.getDouble("chance"),
                    j.getBoolean("possible"),
                    j.getBoolean("supported"),
                    j.getDouble("current"),
                    optional("expected"),
                    optional("best"),
                    optional("rareBest"),
                    if (j.isNull("reason"))null else j.getString("reason"),
                )
            }
        }
    }

    private data class Key(
        val item: Item,
        val goal: Goal,
    )

    private val cache =
        object : LinkedHashMap<Key, Forecast>(128, .75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Key, Forecast>) = size > 512
        }

    private class Limit : RuntimeException()

    private data class Distribution(
        val values: LongArray,
        val probabilities: DoubleArray,
    ) {
        val mean by lazy { values.indices.sumOf { values[it] * probabilities[it] } }
        val prefix by lazy { DoubleArray(values.size + 1).also { p -> for (i in probabilities.indices)p[i + 1] = p[i] + probabilities[i] } }

        fun tail(cut: Long): Double {
            var lo = 0
            var hi = values.size
            while (lo <
                hi
            ) {
                val m = (lo + hi) / 2
                if (values[m] < cut)lo = m + 1 else hi = m
            }
            return (1 - prefix[lo]).coerceIn(0.0, 1.0)
        }

        fun cdf(value: Long): Double {
            var lo = 0
            var hi = values.size
            while (lo <
                hi
            ) {
                val m = (lo + hi) / 2
                if (values[m] <= value)lo = m + 1 else hi = m
            }
            return prefix[lo]
        }
    }

    private data class Allocation(
        val types: List<GearStat>,
        val added: List<Int>,
    )

    private fun allocations(item: Item): Map<Allocation, Double> {
        var states = mapOf(Allocation(item.subs.map { it.type }.sortedBy { it.ordinal }, List(item.subs.size) { 0 }) to 1.0)
        for (e in ((item.enhance / 3 + 1) * 3)..15 step 3) {
            val next = LinkedHashMap<Allocation, Double>()
            for ((a, p) in states) {
                val reveal = item.rank == "Heroic" && e == 12 && a.types.size == 3
                val choices =
                    if (reveal) {
                        GearStat.entries.filter {
                            it != item.mainType &&
                                it !in a.types &&
                                !(item.slot == Slot.WEAPON && it.stat == Stat.DEF) &&
                                !(item.slot == Slot.ARMOR && it.stat == Stat.ATK)
                        }
                    } else {
                        a.types
                    }
                for (t in choices) {
                    val q =
                        if (reveal) {
                            Allocation(a.types + t, a.added + 1)
                        } else {
                            Allocation(
                                a.types,
                                a.added.mapIndexed { i, n ->
                                    n +
                                        if (a.types[i] == t)1 else 0
                                },
                            )
                        }
                    next[q] = (next[q] ?: 0.0) + p / choices.size
                }
            }
            states = next
        }
        return states
    }

    private data class SumKey(
        val level: Int,
        val type: GearStat,
        val n: Int,
    )

    private val sums = HashMap<SumKey, DoubleArray>()

    private fun rollSum(
        level: Int,
        t: GearStat,
        n: Int,
    ): Pair<Int, DoubleArray> {
        val r = Finish.range(t, level)
        val key = SumKey(level, t, n)
        val probabilities =
            synchronized(sums) {
                sums.getOrPut(key) {
                    var p = doubleArrayOf(1.0)
                    repeat(n) {
                        val q = DoubleArray(p.size + r.last - r.first)
                        for (i in p.indices)for (v in 0..r.last - r.first)q[i + v] += p[i] / r.count()
                        p = q
                    }
                    p
                }
            }
        return n * r.first to probabilities
    }

    private fun merge(
        a: Distribution,
        b: Distribution,
    ): Distribution {
        val p = TreeMap<Long, Double>()
        for (i in a.values.indices) {
            for (j in b.values.indices) {
                val v =
                    a.values[i] + b.values[j]
                p[v] = (p[v] ?: 0.0) + a.probabilities[i] * b.probabilities[j]
            }
        }
        return Distribution(p.keys.toLongArray(), p.values.toDoubleArray())
    }

    private fun score(
        main: Long,
        groups: List<Long>,
        k: Int,
    ) = 100 * main + (100 - k) * groups.sum() + k * (groups.maxOrNull() ?: 0L)

    private fun ceilDiv(
        a: Long,
        b: Long,
    ) = -Math.floorDiv(-a, b)

    private fun probability(
        main: Long,
        groups: List<Distribution>,
        k: Int,
        threshold: Long,
        check: () -> Unit,
    ): Double {
        if (score(main, groups.map { it.values.first() }, k) >= threshold)return 1.0
        if (score(main, groups.map { it.values.last() }, k) < threshold)return 0.0
        val ordered = groups.sortedBy { it.values.size }
        val last = ordered.last()
        var nodes = 0

        fun walk(
            i: Int,
            sum: Long,
            max: Long,
        ): Double {
            if (++nodes % 64 == 0)check()
            if (i == ordered.lastIndex) {
                val need = threshold - 100 * main
                val cut =
                    if (k == 100) {
                        if (100 * max >= need)Long.MIN_VALUE / 2 else ceilDiv(need, 100)
                    } else {
                        val n = need - (100 - k) * sum - k * max
                        if (n <= (100 - k) * max)ceilDiv(n, (100 - k).toLong()) else ceilDiv(need - (100 - k) * sum, 100)
                    }
                return last.tail(cut)
            }
            val d = ordered[i]
            return d.values.indices.sumOf { j ->
                d.probabilities[j] * walk(i + 1, sum + d.values[j], maxOf(max, d.values[j]))
            }
        }
        return walk(0, 0, 0).coerceIn(0.0, 1.0)
    }

    private fun expectedMax(groups: List<Distribution>): Double {
        var previous = 0.0
        var mean = 0.0
        for (value in groups.flatMap { it.values.toList() }.distinct().sorted()) {
            val c = groups.fold(1.0) { p, d -> p * d.cdf(value) }
            mean +=
                value * (c - previous)
            previous = c
        }
        return mean
    }

    fun forecast(
        item: Item,
        goal: Goal,
        cancelled: () -> Boolean = { false },
    ): Forecast {
        val key = Key(item, goal)
        synchronized(cache) { cache[key]?.let { return it } }
        val current = goal.model.value(item)

        fun unknown(reason: String) = Forecast(0.0, false, false, current, null, null, null, reason)
        val floor = goal.floor ?: return unknown("No equipped comparison or custom score for this slot")
        if (item.enhance < 15 && !goal.supported)return unknown("Outside the selected forecast coverage")
        if (item.enhance < 15 &&
            (
                item.level !in listOf(85, 88) ||
                    item.rank !in listOf("Epic", "Heroic") ||
                    item.subs.size != (
                        if (item.rank == "Heroic" &&
                            item.enhance < 12
                        ) {
                            3
                        } else {
                            4
                        }
                    ) ||
                    item.subs.any { !it.value.isFinite() || it.value <= 0 }
            )
        ) {
            return unknown("This roll history cannot be forecast")
        }
        val end = System.nanoTime() + goal.budgetMs * 1_000_000L

        fun check() {
            if (cancelled())throw CancellationException()
            if (System.nanoTime() > end)throw Limit()
        }
        val model = goal.model
        val ref = goal.reforge && goal.reforgeAllowed && item.level == 85
        val mainRaw = if (item.enhance == 15)item.mainValue else Finish.main15(item)
        val mainValue = if (ref)Reforge.main(item.mainType, mainRaw) else mainRaw
        val main = (mainValue * model.weight(item.mainType) * model.mainPercent / 100 * SCALE).roundToLong()
        val threshold = (floor * SCALE * 100).roundToLong()
        var chance = 0.0
        var expected = 0.0
        var best = Long.MIN_VALUE
        var rareBest = Long.MIN_VALUE
        try {
            val states =
                if (item.enhance ==
                    15
                ) {
                    mapOf(Allocation(item.subs.map { it.type }, List(item.subs.size) { 0 }) to 1.0)
                } else {
                    allocations(item)
                }
            for ((a, p) in states) {
                check()
                val grouped = LinkedHashMap<Stat, Distribution>()
                val rare = LinkedHashMap<Stat, Long>()
                for (i in a.types.indices) {
                    val t = a.types[i]
                    val n = a.added[i]
                    val s = item.subs.firstOrNull { it.type == t }
                    val bonus = if (ref)Reforge.gain(t, (s?.rolls ?: 0) + n) else 0.0
                    val w = (model.weight(t) * SCALE).roundToLong()
                    val base = (((s?.value ?: 0.0) + bonus) * w).roundToLong()
                    val (offset, prob) = if (n == 0)0 to doubleArrayOf(1.0) else rollSum(item.level, t, n)
                    val d =
                        if (w ==
                            0L
                        ) {
                            Distribution(longArrayOf(0), doubleArrayOf(1.0))
                        } else {
                            Distribution(LongArray(prob.size) { base + (offset + it) * w }, prob)
                        }
                    grouped[t.stat] = grouped[t.stat]?.let { merge(it, d) } ?: d
                    val maximum = if (n == 0)0 else Finish.maximumRoll(t, item.level)
                    rare[t.stat] = (rare[t.stat] ?: 0) + base + n * maximum * w
                }
                val groups = grouped.values.toList()
                chance += p * probability(main, groups, model.diversity, threshold, ::check)
                val groupMean = groups.sumOf { it.mean }
                val largest = if (model.diversity == 0)0.0 else expectedMax(groups)
                expected += p * (main + (1 - model.diversity / 100.0) * groupMean + model.diversity / 100.0 * largest) / SCALE
                best = maxOf(best, score(main, groups.map { it.values.last() }, model.diversity))
                rareBest =
                    maxOf(rareBest, score(main, rare.values.toList(), model.diversity))
            }
            val result =
                Forecast(
                    chance.coerceIn(0.0, 1.0).let {
                        if (it <
                            1e-14
                        ) {
                            0.0
                        } else if (it > 1 - 1e-12) {
                            1.0
                        } else {
                            it
                        }
                    },
                    rareBest >= threshold,
                    true,
                    current,
                    expected,
                    best / (SCALE * 100),
                    rareBest / (SCALE * 100),
                )
            synchronized(cache) { cache[key] = result }
            return result
        } catch (_: Limit) {
            return unknown("Forecast time limit reached; increase the per-piece budget")
        }
    }
}
