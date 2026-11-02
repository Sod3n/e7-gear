package e7.gearscan.opt

import e7.gearscan.calc.Reforge
import e7.gearscan.data.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Keep-rolling curve on the player's own pieces: for each slider chance t, enhance a piece +3 at a time while its
 * chance to reach the ES bar is at least t. Exact over roll destinations; flat-stat values grouped, reforge as its
 * expected bonus, so it stays fast enough to run after every search.
 */
object KeepCurve {
    /** Slider chances, as fractions (0.05% .. 50%). */
    val THRESHOLDS = doubleArrayOf(.0005, .001, .002, .003, .005, .0075, .01, .015, .02, .03, .04, .05, .065, .08, .1, .125, .15, .2, .25, .3, .4, .5)

    /** Totals over the pieces: expected good pieces and enhance gold per threshold, and the finish-everything case. */
    class Result(val good: DoubleArray, val gold: DoubleArray, val possible: Double, val allGold: Double, val pieces: Int, val millis: Long)

    private const val GRID = 20.0 // ES key resolution: 0.05
    private val CUMULATIVE = doubleArrayOf(0.0, 1.0, 2.25, 3.75, 5.75, 8.75, 12.75, 18.25, 25.25, 33.75, 44.25, 57.75, 74.75, 95.75, 122.25, 156.25)

    private fun stepGold(item: Item, from: Int, to: Int) =
        (CUMULATIVE[to] - CUMULATIVE[from]) * (if (item.level >= 88) 400.0 else 350.0) * (if (item.rank == "Heroic") 1.35 else 1.5) * EsForecast.GOLD_PER_XP

    /** One roll of [t] as ES, in at most four equally likely groups (flat stats have up to 46 values). */
    private val rollCache = HashMap<Pair<GearStat, Int>, List<Pair<Double, Double>>>()
    private fun rolls(t: GearStat, level: Int): List<Pair<Double, Double>> = synchronized(rollCache) {
        rollCache.getOrPut(t to level) {
            val r = Finish.range(t, level).toList(); val w = GearScore.weights.getValue(t)
            if (r.size <= 5) r.map { 1.0 / r.size to it * w }
            else (0 until 4).map { g -> val part = r.subList(g * r.size / 4, (g + 1) * r.size / 4); part.size.toDouble() / r.size to part.average() * w }
        }
    }

    /** Per-threshold (good, gold) for one piece, the chance at +15 if always finished, and the gold for that. */
    fun piece(item: Item, bar: Int, reforge: Boolean): Triple<DoubleArray, DoubleArray, Pair<Double, Double>>? {
        if (item.enhance >= 15 || item.level !in listOf(85, 88) || item.rank !in listOf("Epic", "Heroic")) return null
        val heroicOpen = item.rank == "Heroic" && item.enhance < 12
        if (item.subs.size != (if (heroicOpen) 3 else 4) || item.subs.any { !it.value.isFinite() || it.value <= 0 }) return null
        val ref = reforge && item.level == 85
        val need = bar - EsForecast.main15(item, ref)
        val steps = ((item.enhance / 3 + 1) * 3..15 step 3).toList()
        val gold = DoubleArray(steps.size) { k -> stepGold(item, if (k == 0) item.enhance else steps[k - 1], steps[k]) }
        val pool = UpgradeRecord.allowedNew(item)
        val w = GearScore.weights
        val base = item.subs.map { it.type }
        // state = ES of the substats x20 (bits 0-15) | roll count of each line, 3 bits (16-30) | revealed line + 1 (31-34)
        fun lines(key: Long): List<GearStat> { val r = ((key ushr 31) and 15L).toInt(); return if (r == 0) base else base + pool[r - 1] }
        fun count(key: Long, i: Int) = ((key ushr (16 + 3 * i)) and 7L).toInt()
        fun addCount(key: Long, i: Int) = key + (1L shl (16 + 3 * i))
        val rolls = HashMap<GearStat, List<Pair<Double, Long>>>()
        fun steps(t: GearStat) = rolls.getOrPut(t) { rolls(t, item.level).map { (p, v) -> p to (v * GRID).roundToLong() } }
        fun next(key: Long, e: Int, out: ArrayList<Pair<Double, Long>>) {
            out.clear()
            val ls = lines(key)
            if (item.rank == "Heroic" && e == 12 && ls.size == 3) {
                pool.forEachIndexed { r, t -> for ((p, v) in steps(t)) out += p / pool.size to addCount(key + v + ((r + 1).toLong() shl 31), 3) }
            } else for (i in ls.indices) for ((p, v) in steps(ls[i])) out += p / ls.size to addCount(key + v, i)
        }
        fun reaches(key: Long): Boolean {
            val ls = lines(key)
            val bonus = if (ref) ls.indices.sumOf { Reforge.gain(ls[it], count(key, it)) * w.getValue(ls[it]) } else 0.0
            return (key and 0xFFFF).toDouble() / GRID + bonus >= need - 1e-9
        }
        var root = (item.subs.sumOf { it.value * w.getValue(it.type) } * GRID).roundToLong().coerceIn(0, 0xFFFF)
        item.subs.forEachIndexed { i, sub -> root += sub.rolls.coerceIn(0, 7).toLong() shl (16 + 3 * i) }
        val levels = ArrayList<HashMap<Long, Array<Pair<Double, Long>>>>()
        var frontier: Collection<Long> = listOf(root)
        val buf = ArrayList<Pair<Double, Long>>()
        for (e in steps) {
            val m = HashMap<Long, Array<Pair<Double, Long>>>(frontier.size * 2)
            val reached = HashSet<Long>()
            for (k in frontier) { next(k, e, buf); val a = buf.toTypedArray(); m[k] = a; for ((_, n) in a) reached += n }
            levels += m; frontier = reached
        }
        val chance = HashMap<Long, Double>(frontier.size * 2)
        for (k in frontier) chance[k] = if (reaches(k)) 1.0 else 0.0
        for (l in levels.indices.reversed()) for ((k, nx) in levels[l]) { var c = 0.0; for ((p, n) in nx) c += p * chance.getValue(n); chance[k] = c }
        val n = THRESHOLDS.size
        val good = DoubleArray(n); val spent = DoubleArray(n)
        var mass = HashMap<Long, DoubleArray>().apply { put(root, DoubleArray(n) { 1.0 }) }
        for (l in levels.indices) {
            val nextMass = HashMap<Long, DoubleArray>(mass.size * 8)
            for ((k, m) in mass) {
                val c = chance.getValue(k)
                var any = false
                val go = DoubleArray(n) { j -> if (c > 0 && c >= THRESHOLDS[j]) { any = true; m[j] } else 0.0 }
                if (!any) continue
                for (j in 0 until n) spent[j] += go[j] * gold[l]
                for ((p, nx) in levels[l].getValue(k)) { val a = nextMass.getOrPut(nx) { DoubleArray(n) }; for (j in 0 until n) a[j] += p * go[j] }
            }
            mass = nextMass
        }
        for ((k, m) in mass) if (reaches(k)) for (j in 0 until n) good[j] += m[j]
        return Triple(good, spent, chance.getValue(root) to gold.sum())
    }

    /** All pieces in parallel; [bars] by slot. Pieces that can't be modelled are skipped. */
    fun run(items: List<Item>, bars: Map<Slot, Int>, reforge: (Item) -> Boolean, threads: Int = Runtime.getRuntime().availableProcessors()): Result {
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        try {
            val parts = pool.invokeAll(items.mapNotNull { it -> bars[it.slot]?.let { b -> Callable { piece(it, b, reforge(it)) } } }).mapNotNull { it.get() }
            val n = THRESHOLDS.size
            return Result(DoubleArray(n) { j -> parts.sumOf { it.first[j] } }, DoubleArray(n) { j -> parts.sumOf { it.second[j] } },
                parts.sumOf { it.third.first }, parts.sumOf { it.third.second }, parts.size, (System.nanoTime() - started) / 1_000_000)
        } finally { pool.shutdown() }
    }
}
