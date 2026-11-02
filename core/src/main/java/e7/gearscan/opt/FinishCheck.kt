package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.data.Goal
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Whether pieces below +12 can still make one of the player's heroes better. Each hero in [heroes] (setups whose
 * exclusions leave the hero its own gear and the spares) gets a bar, its best build now. A piece is kept when one of
 * [sims] simulated finishes lifts some hero's best build above that bar by [minGain] rolls (one roll = the mean target
 * weight, a speed roll for the speed goal, 1% otherwise), or when, in [rounds] rounds where every piece gets a random
 * finish at once, it is part of a build [comboGain] rolls above the bar.
 *
 * Cheap proofs come first: no legal place (main, sets); a same-set spare in that slot at least as good in every stat the
 * hero scores, or whose shortfall priced at full weight can't reach the bar (cannot beat); the finish dropped into the
 * best build, also with one set piece swapped back in (beats). The rest go to one search per hero and slot with all
 * of them as that slot's only pieces. A search that runs out of time keeps its pieces.
 */
class FinishCheck(private val heroes: List<Setup>, private val sims: Int = 30, private val limitMs: Long = 2000,
                  private val minGain: Double = 0.05, private val comboGain: Double = 1.0, private val comboLimitMs: Long = 20_000) {
    class Result(val kept: Set<Long>, val unknown: Int)

    /** Finished steps and what they found, so a check that was stopped or killed picks up where it was. */
    class Progress(steps: Collection<String> = emptyList(), kept: Collection<Long> = emptyList(), unknown: Collection<Long> = emptyList()) {
        val steps: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet(steps))
        val kept: MutableSet<Long> = java.util.Collections.synchronizedSet(LinkedHashSet(kept))
        val unknown: MutableSet<Long> = java.util.Collections.synchronizedSet(LinkedHashSet(unknown))
    }

    private inner class Hero(val setup: Setup) {
        val req = setup.req
        val hb = setup.hb
        val obj = Objectives.of(req, hb)
        val maxs = setup.maxs()
        val box = setup.box
        val content = setup.content
        val best: List<Item>
        val v0: Double
        init {
            val r = SearchRun(setup, 1); r.run(1, limitMs)
            val b = r.builds().firstOrNull()
            best = b?.items.orEmpty(); v0 = b?.value ?: Double.NaN
        }
        val roll = when (req.goal) {
            Goal.TARGETS -> (0 until 8).filter { !req.targets[it].isNaN() }.map { maxOf(1, req.weights[it]) }.average().takeIf { !it.isNaN() } ?: 1.0
            Goal.SPEED -> Objectives.rollSize(hb)[3]
            else -> 0.01 * kotlin.math.abs(v0)
        }
        val bar = v0 + minGain * roll
        val comboBar = v0 + comboGain * roll
        val cares = (obj.uses.filter { it < 8 } + (0 until 8).filter { !req.mins[it].isNaN() }).distinct().toIntArray()
        // the most one unit of each stat can add to a targets value: its weight per roll
        val slope: DoubleArray? = if (req.goal == Goal.TARGETS) Objectives.rollSize(hb).let { r ->
            DoubleArray(8) { i -> if (req.targets[i].isNaN()) 0.0 else maxOf(1, req.weights[i]) / r[i] } } else null
        val minIdx = (0 until 8).filter { !req.mins[it].isNaN() }
        val fullAlts = req.setAlts.isNotEmpty() && req.setAlts.all { it.values.sum() >= 6 }
        val altSets = req.setAlts.flatMap { it.keys }.toSet()
        val spareVecs = HashMap<Pair<Slot, String>, ArrayList<DoubleArray>>()
        val spares = HashMap<Pair<Slot, String>, ArrayList<Item>>()
        init {
            for (it in box.items) if (setup.eligible(it)) {
                spareVecs.getOrPut(it.slot to it.set) { ArrayList() }.add(Calc.itemVec(it, hb, req.reforged))
                spares.getOrPut(it.slot to it.set) { ArrayList() }.add(it)
            }
        }
        private val slotSetups = java.util.concurrent.ConcurrentHashMap<Slot, Setup>()
        fun slotSetup(sl: Slot) = slotSetups.getOrPut(sl) {
            Setup(setup.hero, setup.game, setup.cfg, req, box, content, setup.canTakeFrom,
                setup.excluded + box.items.filter { it.slot == sl }.map { it.id })
        }

        fun value(items: List<Item>): Double {
            if (req.setAlts.isNotEmpty()) {
                val cnt = items.groupingBy { it.set }.eachCount()
                if (req.setAlts.none { alt -> alt.all { (k, m) -> (cnt[k] ?: 0) >= m } }) return Double.NEGATIVE_INFINITY
            }
            val f = Calc.final(hb, items, req.reforged, content)
            for (x in 0 until 8) {
                if (!req.mins[x].isNaN() && f.stats[x] < req.mins[x]) return Double.NEGATIVE_INFINITY
                if (!maxs[x].isNaN() && f.stats[x] > maxs[x]) return Double.NEGATIVE_INFINITY
            }
            return obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
        }

        /** True: beats the bar; false: cannot; null: needs the search. */
        fun quick(q: Item): Boolean? {
            val m = req.mains[q.slot]
            if (!m.isNullOrEmpty() && q.mainType !in m) return false
            if (fullAlts && q.set !in altSets) return false
            val vq = Calc.itemVec(q, hb, req.reforged)
            val same = spareVecs[q.slot to q.set].orEmpty()
            if (same.any { vp -> cares.all { vp[it] >= vq[it] - 1e-9 } }) return false
            // swapping q for a same-set spare gives a spare build, so q is worth at most v0 plus what it has over that spare
            if (slope != null) {
                val bound = same.filter { vp -> minIdx.all { vp[it] >= vq[it] - 1e-9 } }
                    .minOfOrNull { vp -> (0 until 8).sumOf { i -> slope[i] * maxOf(0.0, vq[i] - vp[i]) } + 1e-4 * maxOf(0.0, vq[8] - vp[8]) }
                if (bound != null && v0 + bound <= bar) return false
            }
            if (best.isEmpty()) return null
            val swapped = best.map { if (it.slot == q.slot) q else it }
            val sw = value(swapped)
            if (sw > bar) return true
            if (sw == Double.NEGATIVE_INFINITY && req.setAlts.isNotEmpty()) {
                // the swap broke a set: refill another slot with a spare of the set the alternative is now one short of
                val cnt = swapped.groupingBy { it.set }.eachCount()
                for (alt in req.setAlts) {
                    val short = alt.filter { (k, n) -> (cnt[k] ?: 0) < n }
                    if (short.size != 1 || short.values.first() - (cnt[short.keys.first()] ?: 0) != 1) continue
                    val need = short.keys.first()
                    for (j in swapped.indices) {
                        val old = swapped[j]
                        if (old.slot == q.slot || (alt[old.set] ?: 0) > 0 && (cnt[old.set] ?: 0) <= alt.getValue(old.set)) continue
                        for (p in spares[old.slot to need].orEmpty())
                            if (value(swapped.mapIndexed { k, it -> if (k == j) p else it }) > bar) return true
                    }
                }
            }
            return null
        }

        /** Of [qs] (all in [sl]), the ids whose best build with them beats the bar; ids left unknown go to [unknown]. */
        fun search(sl: Slot, qs: List<Item>, unknown: MutableSet<Long>): Set<Long> {
            val out = HashSet<Long>()
            var rest = qs
            while (rest.isNotEmpty()) {
                val r = SearchRun(slotSetup(sl), 200, extra = rest, startThreshold = bar)
                r.run(1, limitMs)
                val builds = r.builds()
                val found = builds.filter { it.value > bar }.map { b -> b.items.first { it.slot == sl }.id }.toSet()
                out += found
                if (!r.engine.timedOut && builds.size < 200) break
                if (found.isEmpty()) {
                    if (rest.size == 1) { unknown += rest[0].id; break }
                    rest.chunked((rest.size + 1) / 2).forEach { h -> out += search(sl, h, unknown) }
                    break
                }
                rest = rest.filter { it.id !in found }
            }
            return out
        }
    }

    /** [partners]: other pieces below +12 that get a finish in the rounds too, so pairs with them count. */
    fun check(pieces: List<Item>, partners: List<Item> = emptyList(), rounds: Int = 1, threads: Int = Runtime.getRuntime().availableProcessors(),
              cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> },
              resume: Progress = Progress(), saved: (Progress) -> Unit = {}): Result {
        val ex = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            // each hero's starting build counts as a step too, so the bar moves from the start
            val done = AtomicInteger()
            progress(0, heroes.size * (7 + rounds))
            val hs = ex.invokeAll(heroes.map { s -> java.util.concurrent.Callable { Hero(s).also { progress(done.incrementAndGet(), heroes.size * (7 + rounds)) } } })
                .map { it.get() }.filter { !it.v0.isNaN() }
            // simulated finishes get their own ids (negative) so a search can tell them apart
            val owner = ArrayList<Int>()
            val sims = ArrayList<Item>()
            for ((pi, it) in pieces.withIndex()) for (q in Finish.of(it, this.sims)) {
                sims += Item(-1L - sims.size, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null)
                owner += pi
            }
            val keep = AtomicIntegerArray(pieces.size)
            val index = pieces.withIndex().associate { (i, it) -> it.id to i }
            resume.kept.forEach { id -> index[id]?.let { keep.set(it, 1) } }
            fun step(key: String) {
                resume.steps += key
                resume.kept += pieces.indices.filter { keep.get(it) == 1 }.map { pieces[it].id }
                saved(resume)
            }
            val bySlot = Slot.entries.associateWith { s -> sims.indices.filter { sims[it].slot == s } }
            val tasks = hs.flatMap { h -> Slot.entries.map { sl -> h to sl } }
            val total = heroes.size + tasks.size + rounds * hs.size
            ex.invokeAll(tasks.map { (h, sl) -> java.util.concurrent.Callable {
                val key = "${h.setup.hero.id}:${sl.name}"
                if (!cancelled() && key !in resume.steps) {
                    val open = ArrayList<Item>()
                    for (idx in bySlot.getValue(sl)) {
                        if (keep.get(owner[idx]) == 1) continue
                        when (h.quick(sims[idx])) { true -> keep.set(owner[idx], 1); null -> open += sims[idx]; false -> {} }
                    }
                    val left = open.filter { keep.get(owner[(-1 - it.id).toInt()]) == 0 }
                    val u = HashSet<Long>()
                    if (left.isNotEmpty()) for (id in h.search(sl, left, u)) keep.set(owner[(-1 - id).toInt()], 1)
                    resume.unknown += u.map { pieces[owner[(-1 - it).toInt()]].id }
                    if (!cancelled()) step(key)
                }
                progress(done.incrementAndGet(), total)
            } })
            // rounds: every piece gets one random finish at once; pieces in a build that beats a hero's bar are kept
            for (round in 0 until rounds) {
                if (cancelled()) break
                val offer = (pieces + partners.filter { p -> pieces.none { it.id == p.id } }).mapIndexed { pi, it ->
                    val q = Finish.of(it, 1, kotlin.random.Random(it.id.hashCode() * 31 + round + 1)).first()
                    Item(-1L - pi, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null)
                }
                ex.invokeAll(hs.map { h -> java.util.concurrent.Callable {
                    val key = "round$round:${h.setup.hero.id}"
                    if (!cancelled() && key !in resume.steps) {
                        // a piece the hero can't wear (main, or a set its build has no room for) can't be part of its build
                        var pool = offer.filter { q -> h.req.mains[q.slot].let { m -> m.isNullOrEmpty() || q.mainType in m } && (!h.fullAlts || q.set in h.altSets) }
                        for (k in 0 until 8) {
                            if (pool.isEmpty()) break
                            val r = SearchRun(h.setup, 1, extra = pool, startThreshold = h.comboBar)
                            r.run(1, comboLimitMs)
                            val b = r.builds().firstOrNull()?.takeIf { it.value > h.comboBar } ?: break
                            val used = b.items.filter { it.id < 0 }
                            if (used.isEmpty()) break
                            for (q in used) (-1 - q.id).toInt().takeIf { it < pieces.size }?.let { keep.set(it, 1) }
                            pool = pool.filter { q -> used.none { it.id == q.id } }
                        }
                        if (!cancelled()) step(key)
                    }
                    progress(done.incrementAndGet(), total)
                } })
            }
            val kept = pieces.indices.filter { keep.get(it) == 1 }.map { pieces[it].id }.toSet()
            val unk = resume.unknown.filter { it in index }.toSet() - kept
            return Result(kept + unk, unk.size)
        } finally {
            ex.shutdownNow()
        }
    }
}
