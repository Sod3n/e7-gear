package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.data.Goal
import e7.gearscan.data.Item
import e7.gearscan.data.Match
import e7.gearscan.data.Stat

/** How much a pool run may take from a hero: [alpha] is how many points of gain one point of loss must buy. */
enum class PoolLosses(val label: String, val alpha: Double) {
    SAFE("Never worse", 1000.0),
    SMALL("Small losses", 2.0),
}

/** What one move must earn, in tier-weighted match points. */
enum class PoolEffort(val label: String, val lambda: Double) {
    TIDY("Tidy up", 6.0),
    BALANCED("Balanced", 3.0),
    REBUILD("Rebuild", 1.5),
}

/**
 * A trade not made: [taker] wanted [item], but [holder] would have lost more than the pool gained. Each hero's points
 * before and after the trade (NaN after: the holder had no build good enough without it); [moves] it would have added.
 */
class Denial(val taker: Long, val holder: Long, val item: Long, val takerPoints: Pair<Double, Double> = NaN2,
             val holderPoints: Pair<Double, Double> = NaN2, val moves: Int = 0)

val NaN2 = Double.NaN to Double.NaN

/**
 * Pool run as one objective: the sum over heroes of weight x (gain, or a loss counted [alpha] times) minus [lambda] per move.
 * Each hero searches with every piece priced: [lambda] if it isn't its own, plus what the hero wearing it is expected to lose.
 * A proposal is applied with the heroes it takes from re-searching what is left; it is kept only if the objective rises,
 * else the prices of what it took rise to the loss it caused, and the hero tries again.
 */
class PriceRun(
    private val ids: List<Long>,
    private val weight: (Long) -> Double,
    /** The hero's score in match points for a build (higher is better). */
    private val utility: (Long, Build) -> Double,
    private val current: (Long) -> Build,
    /**
     * Best build with [excluded] off limits, every piece of [required] in it, and each piece costing [cost] points of this
     * hero. Only builds that beat [floor] (no cost counted) and reach [minPoints] are wanted; anything below may be skipped.
     * [relax] drops the hero's set and minimum requirements, for a hero the player made give a piece up.
     */
    private val search: (id: Long, excluded: Set<Long>, required: Set<Long>, floor: Build?, minPoints: Double?, relax: Boolean, cost: (Item) -> Double) -> Build?,
    /** Pieces no hero of the run may take (kept heroes' gear). */
    private val fixed: Set<Long> = emptySet(),
    /** The player's calls on contested pieces: piece -> the hero that keeps it, and piece -> the hero that must get it. */
    private val keep: Map<Long, Long> = emptyMap(),
    private val give: Map<Long, Long> = emptyMap(),
    private val alpha: Double = PoolLosses.SAFE.alpha,
    private val lambda: Double = PoolEffort.BALANCED.lambda,
    private val rounds: Int = 4,
    private val tries: Int = 3,
    private val cancelled: () -> Boolean = { false },
    private val onVisit: (id: Long, round: Int) -> Unit = { _, _ -> },
    private val onAccept: (Map<Long, Build>) -> Unit = {},
) {
    private val base = ids.associateWith { utility(it, current(it)) }
    private val worn = ids.associateWith { id -> current(id).items.map { it.id }.toSet() }
    private val price = HashMap<Long, Double>()
    // Never worse is a rule, not a large alpha: a trade leaving anyone below where it started is refused whatever it gains
    private val strict = alpha >= PoolLosses.SAFE.alpha
    private val asked = ArrayList<Denial>()
    /** Pieces the player gave away that couldn't move: the wearer has no full build at all without them. */
    val unmoved = HashSet<Long>()
    var proposals = 0
        private set
    /** Search count and seconds by kind ("propose r1", "rebuild", ...), to see where a run's time goes. */
    val spent = java.util.TreeMap<String, Pair<Int, Double>>()
    /** Engine seconds and searches that hit the time cap, by hero. */
    val heroTime = HashMap<Long, Pair<Double, Int>>()
    private var round = 1
    /** Bumped on every kept trade; a hero that found nothing at this version has nothing to find until it changes. */
    private var version = 0
    private val settled = HashMap<Long, Int>()

    private fun timed(kind: String, f: () -> Build?): Build? {
        val t = System.nanoTime()
        val b = f()
        val (n, sec) = spent[kind] ?: (0 to 0.0)
        spent[kind] = (n + 1) to (sec + (System.nanoTime() - t) / 1e9)
        return b
    }

    private fun phi(d: Double) = if (d >= 0) d else alpha * d

    private fun moves(id: Long, b: Build) = b.items.count { !it.ghost && (it.id !in worn.getValue(id) || it.mod != null) }

    private fun score(id: Long, b: Build) = weight(id) * phi(utility(id, b) - base.getValue(id)) - lambda * moves(id, b)

    private fun excludedFor(id: Long) = fixed + keep.filterValues { it != id }.keys + give.filterValues { it != id }.keys

    private fun requiredFor(id: Long) = give.filterValues { it == id }.keys

    /** Every hero's build at the end, changed or not. */
    fun run(): Map<Long, Build> {
        val assign = LinkedHashMap<Long, Build>()
        ids.forEach { assign[it] = current(it) }
        val order = ids.sortedByDescending { weight(it) * (100 - base.getValue(it)) }
        for (taker in give.values.distinct()) if (taker in assign && !cancelled()) { if (force(taker, assign)) onAccept(LinkedHashMap(assign)) }
        for (r in 1..rounds) {
            round = r
            var accepted = 0
            for (id in order) {
                if (cancelled()) return assign
                onVisit(id, r)
                if (settled[id] == version) continue
                if (step(id, assign)) { accepted++; version++; onAccept(LinkedHashMap(assign)) }
            }
            if (accepted == 0) break
        }
        return assign
    }

    /** Pieces asked for that stayed with their wearer in [result], with the latest numbers for each. */
    fun refusals(result: Map<Long, Build>): List<Denial> {
        val owner = HashMap<Long, Long>()
        for ((id, b) in result) b.items.forEach { owner[it.id] = id }
        return asked.asReversed().filter { owner[it.item] == it.holder }.distinctBy { it.taker to it.item }
    }

    /** The player gave pieces to [taker]: it takes them whatever its wearers lose, and they rebuild from what is left. */
    private fun force(taker: Long, assign: LinkedHashMap<Long, Build>): Boolean {
        val b = search(taker, excludedFor(taker), requiredFor(taker), null, null, false) { item ->
            (if (item.mod != null || item.id !in worn.getValue(taker)) lambda else 0.0) / weight(taker)
        } ?: return false.also { unmoved += requiredFor(taker) }
        val owner = owners(assign)
        val trial = LinkedHashMap(assign)
        trial[taker] = b
        for (g in b.items.mapNotNull { owner[it.id]?.takeIf { h -> h != taker } }.distinct()) {
            val held = trial.filterKeys { it != g }.values.flatMap { bb -> bb.items.map { it.id } }.toSet()
            val cost = { item: Item -> (if (item.mod != null || item.id !in worn.getValue(g)) lambda else 0.0) / weight(g) }
            // the player chose this loss: a wearer whose sets no longer fit still gets a full build, just a worse one
            trial[g] = search(g, excludedFor(g) + held, requiredFor(g), null, null, false, cost)
                ?: search(g, excludedFor(g) + held, requiredFor(g), null, null, true, cost)
                ?: return false.also { unmoved += requiredFor(taker).filter { owner[it] == g } }
        }
        assign.putAll(trial)
        return true
    }

    private fun owners(assign: Map<Long, Build>): Map<Long, Long> {
        val o = HashMap<Long, Long>()
        for ((id, b) in assign) b.items.forEach { if (!it.ghost) o[it.id] = id }
        return o
    }

    /** One hero's turn: up to [tries] proposals; true if one was kept. */
    private fun step(id: Long, assign: LinkedHashMap<Long, Build>): Boolean {
        repeat(tries) { attempt ->
            val owner = owners(assign)
            val w = weight(id)
            val old = assign.getValue(id)
            val mine = old.items.associate { it.id to it.mod }
            val cost = { it: Item ->
                val own = owner[it.id] == id
                // what the hero already has costs nothing again, a mod it already took included
                val move = when {
                    own && mine[it.id] == it.mod -> 0.0
                    it.mod != null || it.id !in worn.getValue(id) -> lambda
                    else -> 0.0
                }
                val holder = owner[it.id]?.takeIf { h -> h != id }
                // until a trade proves otherwise, a worn piece is guessed to cost its wearer one match point
                (move + if (holder != null) price.getOrElse(it.id) { weight(holder) } else 0.0) / w
            }
            // nothing better at today's prices: only another hero's trade can change that (refusals only raise prices)
            val b = timed("propose r$round" + if (attempt > 0) " retry" else "") { search(id, excludedFor(id), requiredFor(id), old, null, false, cost) }
                ?: return false.also { if (attempt == 0) settled[id] = version }
            if (b.items.map { it.id to it.mod } == old.items.map { it.id to it.mod }) return false.also { if (attempt == 0) settled[id] = version }
            proposals++
            val trial = LinkedHashMap(assign)
            trial[id] = b
            val taken = b.items.filter { !it.ghost }.mapNotNull { item -> owner[item.id]?.takeIf { it != id }?.let { h -> h to item.id } }
            val losers = taken.map { it.first }.distinct()
            val gain = maxOf(0.0, score(id, b) - score(id, old))
            for (g in losers) {
                // the trade only pays if the loser stays above this, so its search may skip anything lower
                val least = base.getValue(g) - if (strict) 0.0 else gain / (alpha * weight(g))
                // the loser rebuilds from what nobody else in the trial holds, paying only for moves
                val held = trial.filterKeys { it != g }.values.flatMap { bb -> bb.items.map { it.id } }.toSet()
                val rebuildCost = { item: Item -> (if (item.mod != null || item.id !in worn.getValue(g)) lambda else 0.0) / weight(g) }
                // first only the slots it lost, keeping the rest: a few candidates instead of the whole box; a full search only if that falls short
                val lost = taken.filter { it.first == g }.map { it.second }.toSet()
                val rest = assign.getValue(g).items.filter { !it.ghost && it.id !in lost }.map { it.id }.toSet()
                val gb = timed("rebuild slots") { search(g, excludedFor(g) + held, requiredFor(g) + rest, null, least, false, rebuildCost) }
                    ?: timed("rebuild full") { search(g, excludedFor(g) + held, requiredFor(g), null, least, false, rebuildCost) }
                // no rebuild keeps the loser where it must be: that loss is too high to pay, not zero
                if (gb == null) { trial.remove(g); raise(taken, assign, trial, id); return@repeat }
                trial[g] = gb
            }
            val delta = (listOf(id) + losers).sumOf { score(it, trial.getValue(it)) - score(it, assign.getValue(it)) }
            val hurts = strict && losers.any { utility(it, trial.getValue(it)) < base.getValue(it) - 1e-9 }
            if (delta > 1e-6 && !hurts) { assign.putAll(trial); return true }
            raise(taken, assign, trial, id)
        }
        return false
    }

    /** Spread each loser's weighted loss over the pieces taken from it, so the next proposal sees what they cost. */
    private fun raise(taken: List<Pair<Long, Long>>, assign: Map<Long, Build>, trial: Map<Long, Build>, taker: Long) {
        for ((g, items) in taken.groupBy({ it.first }, { it.second })) {
            val loss = score(g, assign.getValue(g)) - (trial[g]?.let { score(g, it) } ?: Double.NEGATIVE_INFINITY)
            val each = if (loss.isInfinite()) 1e6 else maxOf(0.0, loss) / items.size + lambda
            val tp = utility(taker, assign.getValue(taker)) to (trial[taker]?.let { utility(taker, it) } ?: Double.NaN)
            val hp = utility(g, assign.getValue(g)) to (trial[g]?.let { utility(g, it) } ?: Double.NaN)
            val more = trial.entries.sumOf { (h, b) -> moves(h, b) - moves(h, assign.getValue(h)) }
            for (item in items) {
                price[item] = maxOf(price[item] ?: 0.0, each)
                asked += Denial(taker, g, item, tp, hp, more)
            }
        }
    }
}

/** A hero's score for the pool run: match % unrounded for targets, so small trades count; other goals 1 point per 1% over now. */
class PoolPoints(private val s: Setup, now: Build) {
    private val obj = Objectives.of(s.req, s.hb)
    private val nowValue = value(now)
    private val floor = Calc.final(s.hb, Ghost.mainsOnly(s.req), s.req.reforged, s.content)
    private val roll = Objectives.rollSize(s.hb)
    private val start = shortfall { floor[it] }
    private val targets = s.req.goal == Goal.TARGETS

    private fun value(b: Build) = obj.eval(b.final.stats + doubleArrayOf(b.final.gs), b.final.dmgMult, b.final.pen)

    /** Weighted rolls short of the targets. */
    private fun shortfall(stat: (Stat) -> Double): Double {
        var miss = 0.0
        for (st in Stat.entries) {
            val t = s.req.targets[st.ordinal]
            if (!t.isNaN()) miss += s.req.weights[st.ordinal].coerceAtLeast(1) * maxOf(0.0, t - stat(st)) / roll[st.ordinal]
        }
        return miss
    }

    fun of(b: Build): Double {
        if (!targets) return 100 * (value(b) / nowValue - 1)
        var pct = if (start <= 0) 100.0 else minOf(100.0, 100 * (1 - shortfall { b.final[it] } / start))
        for (m in Match.missingSets(b.items.groupingBy { it.set }.eachCount(), s.req.setAlts) { s.content.set(it) }) pct *= 1 - Match.MISSING_SET_PER_PIECE * m.pieces
        return pct
    }

    /** The least plain search value a build scoring [points] can have (targets: shortfall only, so the gear score bonus is ignored). */
    fun valueAt(points: Double): Double = if (targets) -start * (1 - points / 100) else nowValue * (1 + points / 100)

    /** Search units per point: rolls for targets, 1% of now's value for other goals. */
    val units: Double get() = if (targets) maxOf(1e-6, start / 100) else Math.abs(nowValue) / 100
}

/**
 * The pool run over real heroes. [setup] makes a hero's search with pieces off limits (null if it can't run);
 * [run] runs one search (the app shows progress and stops it). Tiers are the weights.
 */
fun pricedPool(
    ids: List<Long>, tier: (Long) -> Int, keepGear: Set<Long>, setup: (Long, Set<Long>) -> Setup?, run: (Setup, SearchRun) -> Unit,
    losses: PoolLosses, effort: PoolEffort, cancelled: () -> Boolean = { false },
    onVisit: (Long, Int) -> Unit = { _, _ -> }, onAccept: (Map<Long, Build>) -> Unit = {}, rounds: Int = 4, tries: Int = 3,
    keep: Map<Long, Long> = emptyMap(), give: Map<Long, Long> = emptyMap(),
): PriceRun {
    val now = HashMap<Long, Build>()
    val points = HashMap<Long, PoolPoints>()
    for (id in ids) setup(id, emptySet())?.let { s -> s.build(s.current().values.toList()).let { b -> now[id] = b; points[id] = PoolPoints(s, b) } }
    val usable = ids.filter { it in now }
    lateinit var pr: PriceRun
    fun note(kind: String, t0: Long) { val (n, sec) = pr.spent[kind] ?: (0 to 0.0); pr.spent[kind] = (n + 1) to (sec + (System.nanoTime() - t0) / 1e9) }
    pr = PriceRun(usable, { tier(it).toDouble() }, { id, b -> points.getValue(id).of(b) }, { now.getValue(it) },
        search = { id, excluded, required, floor, minPoints, relax, cost ->
            // a required piece is the only one its slot may use
            val slots = if (required.isEmpty()) emptySet() else setup(id, excluded)?.box?.let { b -> required.mapNotNull { b.itemsById[it]?.slot } }.orEmpty().toSet()
            val others = if (slots.isEmpty()) emptySet() else setup(id, excluded)!!.box.items.filter { it.slot in slots && it.id !in required }.map { it.id }.toSet()
            setup(id, excluded + others)?.let { s0 ->
                val s = if (!relax) s0 else Setup(s0.hero, s0.game, s0.cfg, s0.req.copy().apply { setAlts = emptyList(); mins.fill(Double.NaN); maxChanges = 6 },
                    s0.box, s0.content, s0.canTakeFrom, s0.excluded)
                val p = points.getValue(id)
                val k = p.units
                // the build the hero already has costs nothing, so a proposal must beat its plain value
                val beat = floor?.let { b -> Objectives.of(s.req, s.hb).eval(b.final.stats + doubleArrayOf(b.final.gs), b.final.dmgMult, b.final.pen) - 1e-9 }
                // a rebuild pays at most six moves, so its value with costs is at least this when it reaches minPoints
                val reach = minPoints?.let { m -> p.valueAt(m) - 6 * effort.lambda / tier(id) * k - 1e-9 }
                val start = listOfNotNull(beat, reach).maxOrNull() ?: Double.NEGATIVE_INFINITY
                val t0 = System.nanoTime()
                val r = SearchRun(s, 1, startThreshold = start, cost = { item -> cost(item) * k })
                note("· setup", t0)
                val t1 = System.nanoTime()
                run(s, r)
                note("· engine", t1)
                val (sec, capped) = pr.heroTime[id] ?: (0.0 to 0)
                pr.heroTime[id] = (sec + (System.nanoTime() - t1) / 1e9) to (capped + if (r.engine.timedOut) 1 else 0)
                r.builds().firstOrNull()
            }
        }, fixed = keepGear, keep = keep, give = give, alpha = losses.alpha, lambda = effort.lambda, rounds = rounds, tries = tries, cancelled = cancelled,
        onVisit = onVisit, onAccept = onAccept)
    return pr
}
