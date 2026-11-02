package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.calc.GS
import e7.gearscan.data.Goal
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.data.UpgradeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pieces below +15 that could make a hero's best build better once finished. A hero's bar is its best build now (own
 * gear and spares, every piece at its current level, so a piece only gets credit for what finishing it adds). Every
 * piece gets [sims] simulated finishes ([Finish]), the same for every hero; a finish counts for a hero when the hero's
 * best complete build with it beats the bar by [MIN_GAIN] rolls. Builds are whole-search answers, never slot swaps.
 *
 * Exact proofs skip most finishes: a same-set piece the hero can already use is at least as good in every stat it
 * scores (and no higher on a capped stat), or beats it by too little to matter. The rest go to one search per hero and
 * slot that finds each finish's own best build ([Engine] distinct slot). A search that runs out of time leaves its
 * unsettled finishes unknown; every hero and slot is a checkpoint, so a stopped check continues where it was.
 */
class HeroUpgrades(
    private val sims: Int = SIMS,
    private val baseMs: Long = 4000,
    private val slotMs: Long = 4000,
    private val log: ((String) -> Unit)? = null,
) {
    /** A hero's best build before any upgrade. [value] null: no build meets the setup ([timedOut]: not proven). */
    /** [worn]: the value of what the hero wears, when that is a complete build that meets its setup. */
    class Base(
        val id: Long,
        val value: Double?,
        val items: List<Long>,
        val timedOut: Boolean,
        val unit: Double,
        val budget: Long,
        val worn: Double? = null,
    ) {
        val bar get() = value?.let { it + MIN_GAIN * unit } ?: Double.NEGATIVE_INFINITY

        /** Couldn't even find the starting build: nothing about this hero is known. */
        val unresolved get() = value == null && timedOut

        fun toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("value", value ?: JSONObject.NULL)
                .put("items", JSONArray(items))
                .put("timedOut", timedOut)
                .put("unit", unit)
                .put("budget", budget)
                .put("worn", worn ?: JSONObject.NULL)

        companion object {
            fun of(j: JSONObject) =
                Base(
                    j.getLong("id"),
                    if (j.isNull("value")) null else j.getDouble("value"),
                    longs(j.getJSONArray("items")),
                    j.getBoolean("timedOut"),
                    j.getDouble("unit"),
                    j.getLong("budget"),
                    if (!j.has("worn") ||
                        j.isNull("worn")
                    ) {
                        null
                    } else {
                        j.getDouble("worn")
                    },
                )
        }
    }

    /**
     * One hero-slot search: finishes (indexes into [finishes]) whose best build beats the bar, with its value; [swaps]: those
     * that beat it just put in place of the bar build's piece in that slot (nothing else moves); and the unsettled ones.
     */
    class Step(
        val hits: Map<Int, Double>,
        val best: Map<Int, List<Long>>,
        val unknown: List<Int>,
        val budget: Long,
        val swaps: Map<Int, Double> = emptyMap(),
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("hits", pairs(hits))
                .put("swaps", pairs(swaps))
                .put("best", JSONArray(best.entries.map { (k, v) -> JSONArray().put(k).put(JSONArray(v)) }))
                .put("unknown", JSONArray(unknown))
                .put("budget", budget)

        companion object {
            fun of(j: JSONObject): Step {
                val b = j.getJSONArray("best")
                return Step(
                    pairsOf(j.getJSONArray("hits")),
                    (0 until b.length()).associate {
                        b.getJSONArray(it).let { e ->
                            e.getInt(0) to
                                longs(e.getJSONArray(1))
                        }
                    },
                    j.getJSONArray("unknown").let { a ->
                        (0 until a.length()).map(a::getInt)
                    },
                    j.getLong("budget"),
                    j.optJSONArray("swaps")?.let(::pairsOf).orEmpty(),
                )
            }
        }
    }

    /** What is done so far; saved after each step so a stopped or killed check picks up where it was. */
    class Progress(
        bases: Map<Long, Base> = emptyMap(),
        steps: Map<String, Step> = emptyMap(),
    ) {
        val bases = ConcurrentHashMap(bases)
        val steps = ConcurrentHashMap(steps)

        fun toJson(): JSONObject =
            JSONObject()
                .put("bases", JSONArray(bases.values.map { it.toJson() }))
                .put("steps", JSONObject().also { o -> steps.forEach { (k, v) -> o.put(k, v.toJson()) } })

        companion object {
            fun of(j: JSONObject): Progress {
                val b = j.getJSONArray("bases")
                val s = j.getJSONObject("steps")
                return Progress(
                    (0 until b.length()).map { Base.of(b.getJSONObject(it)) }.associateBy { it.id },
                    s.keys().asSequence().associateWith { Step.of(s.getJSONObject(it)) },
                )
            }
        }
    }

    /**
     * A piece for a hero: [values] are the hero's best build values with each finish that beat the bar, best first (gain
     * = value - the base value; with no base build, the value of the first build it makes possible). [example] is the
     * build behind the best one, with [finish] in the piece's slot; [changes] its final-stat differences from the base build.
     */
    class Pick(
        val piece: Long,
        val hero: Long,
        val values: List<Double>,
        val unknown: Int,
        val example: List<Long>,
        val finish: Item?,
        val changes: String,
        /** Finish (index in its piece's [sims]) behind each of [values]. */
        val hitIdx: List<Int> = emptyList(),
        /** Finishes that beat the bar put in place of the bar build's piece, nothing else moving: (finish, value), best first. */
        val swaps: List<Pair<Int, Double>> = emptyList(),
        /** The best of [swaps]' final-stat differences from the base build. */
        val swapChanges: String = "",
        /** [example] against the base build: other pieces it moves, and its sets when they differ ("" when the same). */
        val moved: Int = 0,
        val sets: String = "",
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("piece", piece)
                .put("hero", hero)
                .put("values", JSONArray(values))
                .put("unknown", unknown)
                .put(
                    "example",
                    JSONArray(example),
                ).put("finish", finish?.let(UpgradeRecord::itemJson) ?: JSONObject.NULL)
                .put("changes", changes)
                .put("hitIdx", JSONArray(hitIdx))
                .put(
                    "swaps",
                    JSONArray(
                        swaps.map { (i, v) ->
                            JSONArray().put(i).put(v)
                        },
                    ),
                ).put("swapChanges", swapChanges)
                .put("moved", moved)
                .put("sets", sets)

        companion object {
            fun of(j: JSONObject) =
                Pick(
                    j.getLong("piece"),
                    j.getLong("hero"),
                    j.getJSONArray("values").let { a -> (0 until a.length()).map(a::getDouble) },
                    j.getInt("unknown"),
                    longs(j.getJSONArray("example")),
                    j.optJSONObject("finish")?.let {
                        e7.gearscan.data.Box
                            .parseItem(it)
                    },
                    j.getString("changes"),
                    j.optJSONArray("hitIdx")?.let { a -> (0 until a.length()).map(a::getInt) }.orEmpty(),
                    j
                        .optJSONArray("swaps")
                        ?.let { a ->
                            (0 until a.length()).map { a.getJSONArray(it).let { e -> e.getInt(0) to e.getDouble(1) } }
                        }.orEmpty(),
                    j.optString("swapChanges"),
                    j.optInt("moved"),
                    j.optString("sets"),
                )
        }
    }

    /** [checked]: pieces tried; [sims]: finishes each. */
    class Result(
        val bases: Map<Long, Base>,
        val picks: List<Pick>,
        val unknownSteps: Int,
        val checked: Int,
        val sims: Int = SIMS,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("bases", JSONArray(bases.values.map { it.toJson() }))
                .put("picks", JSONArray(picks.map { it.toJson() }))
                .put("unknownSteps", unknownSteps)
                .put("checked", checked)
                .put("sims", sims)

        companion object {
            /** Picks of pieces no longer in [items] drop out. */
            fun of(
                j: JSONObject,
                items: Map<Long, Item>,
            ): Result {
                val b = j.getJSONArray("bases")
                val p = j.getJSONArray("picks")
                return Result(
                    (0 until b.length()).map { Base.of(b.getJSONObject(it)) }.associateBy { it.id },
                    (0 until p.length()).map { Pick.of(p.getJSONObject(it)) }.filter { it.piece in items },
                    j.getInt("unknownSteps"),
                    j.getInt("checked"),
                    j.optInt("sims", SIMS),
                )
            }
        }
    }

    private fun key(
        hero: Long,
        slot: Slot,
    ) = "$hero:${slot.name}"

    private val active: MutableSet<SearchRun> = ConcurrentHashMap.newKeySet()

    @Volatile private var stopped = false

    /** Stops the searches running now; [run] returns soon after, saving nothing it didn't finish. */
    fun cancel() {
        stopped = true
        active.forEach { it.cancel() }
    }

    private fun search(
        r: SearchRun,
        budget: Long,
        threads: Int = 1,
    ) {
        active += r
        try {
            if (!stopped) r.run(threads, budget)
        } finally {
            active -= r
        }
    }

    /**
     * [setups]: the heroes, each with its own exclusions (what it may not take). [pieces]: the candidates, all supported
     * by [Finish]. Every hero and slot first gets one thread and the short budget; steps that leave finishes open then get
     * every thread and [REST]x the budget, one at a time. [attempt] doubles both; steps already settled are kept.
     */
    fun run(
        setups: List<Setup>,
        pieces: List<Item>,
        attempt: Int = 0,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
        resume: Progress = Progress(),
        saved: (Progress) -> Unit = {},
    ): Result {
        require(pieces.all { UpgradeRecord.supported(it) && it.enhance < 15 })
        val baseBudget = baseMs shl attempt
        val stepBudget = slotMs shl attempt
        val all = finishes(pieces, sims)
        val bySlot = Slot.entries.associateWith { sl -> pieces.indices.filter { pieces[it].slot == sl } }
        val restBudget = stepBudget * REST
        val total = AtomicInteger(setups.size * 7)
        val done = AtomicInteger()
        val ex = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val checks =
                ex
                    .invokeAll(
                        setups.map { s ->
                            Callable {
                                val old = resume.bases[s.hero.id]
                                val base =
                                    if (old == null && (cancelled() || stopped)) {
                                        Base(s.hero.id, null, emptyList(), true, 1.0, 0)
                                    } else if (old != null && (!old.timedOut || old.budget >= baseBudget || cancelled() || stopped)) {
                                        old
                                    } else {
                                        val fresh = baseline(s, baseBudget)
                                        if (cancelled() ||
                                            stopped
                                        ) {
                                            return@Callable Check(s, old ?: fresh).also { progress(done.incrementAndGet(), total.get()) }
                                        }
                                        // a different starting build makes every finished step of this hero stale
                                        if (old != null &&
                                            (old.value != fresh.value || old.items != fresh.items)
                                        ) {
                                            Slot.entries.forEach { resume.steps.remove(key(s.hero.id, it)) }
                                        }
                                        resume.bases[s.hero.id] = fresh
                                        saved(resume)
                                        fresh
                                    }
                                progress(done.incrementAndGet(), total.get())
                                Check(s, base)
                            }
                        },
                    ).map { it.get() }
            ex.invokeAll(
                checks.flatMap { c ->
                    Slot.entries.map { sl ->
                        Callable {
                            val k = key(c.s.hero.id, sl)
                            val old = resume.steps[k]
                            val keep = old != null && (old.unknown.isEmpty() || old.budget >= stepBudget)
                            if (!keep && !cancelled() && !stopped && !c.base.unresolved) {
                                val step = c.step(sl, bySlot.getValue(sl), pieces, all, stepBudget, 1) { cancelled() || stopped }
                                if (!cancelled() && !stopped) {
                                    resume.steps[k] = step
                                    saved(resume)
                                }
                            }
                            progress(done.incrementAndGet(), total.get())
                        }
                    }
                },
            )
            val rest =
                checks.flatMap { c -> Slot.entries.map { c to it } }.filter { (c, sl) ->
                    resume.steps[key(c.s.hero.id, sl)]?.let { it.unknown.isNotEmpty() && it.budget < restBudget } == true
                }
            total.addAndGet(rest.size)
            for ((c, sl) in rest) {
                if (cancelled() || stopped) break
                val step = c.step(sl, bySlot.getValue(sl), pieces, all, restBudget, threads) { cancelled() || stopped }
                if (!cancelled() && !stopped) {
                    resume.steps[key(c.s.hero.id, sl)] = step
                    saved(resume)
                }
                progress(done.incrementAndGet(), total.get())
            }
            return result(checks, pieces, all, resume)
        } finally {
            ex.shutdownNow()
        }
    }

    /** Final-stat differences of [build] from [base], only for what the hero's goal scores: other stats moving says nothing about the gain. */
    private fun changes(
        c: Check,
        base: Build,
        build: Build,
    ) = Stat.entries
        .filter { it.ordinal in c.cares }
        .mapNotNull { stat ->
            val delta = build.final.stats[stat.ordinal] - base.final.stats[stat.ordinal]
            if (kotlin.math.abs(delta) <
                0.5
            ) {
                null
            } else {
                "${stat.short} ${if (delta > 0) "+" else ""}${e7.gearscan.data.fmtNum(kotlin.math.round(delta))}"
            }
        }.joinToString(" · ")

    private fun result(
        checks: List<Check>,
        pieces: List<Item>,
        all: List<Item>,
        p: Progress,
    ): Result {
        val picks = ArrayList<Pick>()
        var unknownSteps = 0
        for (c in checks) {
            val baseBuild =
                c.base.items
                    .takeIf { it.size == 6 }
                    ?.let { ids ->
                        ids.mapNotNull { c.s.box.itemsById[it] }.takeIf {
                            it.size ==
                                6
                        }
                    }?.let { c.s.build(it, c.base.value ?: Double.NaN) }
            for (sl in Slot.entries) {
                val step = p.steps[key(c.s.hero.id, sl)]
                if (step == null) {
                    if (!c.base.unresolved) unknownSteps++
                    continue
                }
                if (step.unknown.isNotEmpty()) unknownSteps++
                val hitsBy = step.hits.entries.groupBy { it.key / sims }
                val swapsBy = step.swaps.entries.groupBy { it.key / sims }
                val unknownBy = step.unknown.groupingBy { it / sims }.eachCount()
                // a piece can beat what the hero wears without beating its best build: it is listed for that alone
                for (pi in (hitsBy.keys + unknownBy.keys + swapsBy.keys).toSortedSet()) {
                    val ranked = hitsBy[pi].orEmpty().sortedByDescending { it.value }
                    val values = ranked.map { it.value }
                    val swaps = swapsBy[pi].orEmpty().sortedByDescending { it.value }.map { it.key % sims to it.value }
                    val ids = step.best[pi].orEmpty()
                    val finish = ids.firstOrNull { it < 0 }?.let { all[simIndex(it)] }
                    val example = ids.map { if (it < 0) pieces[pi].id else it }
                    val build =
                        finish
                            ?.let { f -> ids.map { id -> if (id < 0) f else c.s.box.itemsById[id] } }
                            ?.takeIf { b -> b.all { it != null } }
                            ?.let { b -> c.s.build(b.map { it!! }) }
                    val changes = if (baseBuild != null && build != null) changes(c, baseBuild, build) else ""
                    val worn =
                        c.s
                            .current()
                            .values
                            .toList()
                            .takeIf { it.size == 6 }
                            ?.let { c.s.build(it) }
                    val swapBuild =
                        swaps
                            .firstOrNull()
                            ?.let { (j, _) ->
                                worn?.items?.map {
                                    if (it.slot ==
                                        sl
                                    ) {
                                        all[pi * sims + j]
                                    } else {
                                        it
                                    }
                                }
                            }?.let { c.s.build(it) }
                    val swapChanges = if (worn != null && swapBuild != null) changes(c, worn, swapBuild) else ""
                    val moved =
                        if (baseBuild != null &&
                            build != null
                        ) {
                            build.items.count { it.slot != sl && baseBuild.items.first { b -> b.slot == it.slot }.id != it.id }
                        } else {
                            0
                        }
                    val sets =
                        if (baseBuild != null &&
                            build != null
                        ) {
                            setsLabel(build.items, c.s.content).takeIf { it != setsLabel(baseBuild.items, c.s.content) }.orEmpty()
                        } else {
                            ""
                        }
                    picks +=
                        Pick(
                            pieces[pi].id,
                            c.s.hero.id,
                            values,
                            unknownBy[pi] ?: 0,
                            example,
                            finish?.let { asPiece(it, pieces[pi]) },
                            changes,
                            ranked.map { it.key % sims },
                            swaps,
                            swapChanges,
                            moved,
                            sets,
                        )
                }
            }
        }
        return Result(checks.associate { it.s.hero.id to it.base }, picks, unknownSteps, pieces.size, sims)
    }

    /**
     * One piece being enhanced, for one hero. [bar]: the better of the hero's best build without the piece ([without]) and
     * its best build with the piece as it is now; [now]: the piece as it is already beats the first. [hits]: finish index ->
     * build value, finishes from [Finish.of] the piece as it is now; [minimal]: the weakest finishes that still beat the bar.
     * The swap fields ask the same with nothing else moving: what the hero wears, the piece put in its slot.
     */
    class PieceResult(
        val hero: Long,
        val without: Base,
        val bar: Base,
        val now: Boolean,
        val hits: Map<Int, Double>,
        val unknown: Int,
        val total: Int,
        val minimal: List<Item>,
        val changes: String,
        /** The same with nothing else moving: finishes put in place of the piece in the bar build's slot. */
        val swapHits: Map<Int, Double> = emptyMap(),
        val swapMinimal: List<Item> = emptyList(),
        val swapChanges: String = "",
        /** The piece as it is, put into the best build without it, beats that build: equipping it alone helps. */
        val swapNow: Boolean = false,
        val swapNowValue: Double? = null,
        /** Builds to show: the bar build with the piece's usual helping finish (or the piece as it is), and the best rebuild. */
        val swapBuild: List<Item>? = null,
        val rebuildBuild: List<Item>? = null,
        /** What the hero wears, and the value the swap fields must beat (the better of that and the piece as it is in it). */
        val worn: Base? = null,
        val swapRef: Double? = null,
    ) {
        val provisional get() = bar.timedOut
    }

    /**
     * [current]: the piece as recorded now, whose id is its box id. Each hero with a setup that may wear it gets a [PieceResult],
     * handed to [done] as soon as it is ready. [without] keeps each hero's best build without the piece between calls: recording
     * a step changes only the piece, so only its own slot is searched again. Heroes in [skip] are left out.
     */
    fun piece(
        setups: List<Setup>,
        current: Item,
        without: MutableMap<Long, Base>,
        attempt: Int = 0,
        skip: Set<Long> = emptySet(),
        threads: Int = Runtime.getRuntime().availableProcessors(),
        cancelled: () -> Boolean = { false },
        done: (PieceResult) -> Unit = {},
    ): List<PieceResult> {
        val baseBudget = baseMs shl attempt
        val stepBudget = slotMs shl attempt
        val sl = current.slot
        // ids no box item has: the piece as it is, and its finishes (-1 - j)
        val now =
            Item(
                -1_000_000L,
                sl,
                current.set,
                current.rank,
                current.level,
                current.enhance,
                current.mainType,
                current.mainValue,
                current.subs,
                current.locked,
                current.equippedOn,
                main15 = current.main15,
            )
        val finishes = if (current.enhance >= 15) emptyList() else finishes(listOf(current), sims)
        val ex = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            return ex
                .invokeAll(
                    setups.filter { it.hero.id !in skip }.map { s0 ->
                        Callable<PieceResult?> {
                            if (cancelled() || stopped) return@Callable null
                            val req = s0.req
                            if (current.id in s0.excluded ||
                                (!req.allowLocked && current.locked && current.equippedOn != s0.hero.id) ||
                                req.mains[sl].let { !it.isNullOrEmpty() && current.mainType !in it } ||
                                (
                                    req.setAlts.isNotEmpty() &&
                                        req.setAlts.all { it.values.sum() >= 6 } &&
                                        req.setAlts.none { current.set in it }
                                )
                            ) {
                                // a hero that can't wear it is settled too: nothing to gain
                                val none = Base(s0.hero.id, null, emptyList(), false, 1.0, 0)
                                return@Callable PieceResult(
                                    s0.hero.id,
                                    none,
                                    none,
                                    false,
                                    emptyMap(),
                                    0,
                                    finishes.size,
                                    emptyList(),
                                    "",
                                ).also(done)
                            }
                            // the piece never comes from the box: its old self is excluded, its present self is [now]
                            val s = Setup(s0.hero, s0.game, s0.cfg, req, s0.box, s0.content, s0.canTakeFrom, s0.excluded + current.id)
                            val w =
                                without[s.hero.id]?.takeIf { !it.timedOut || it.budget >= baseBudget } ?: baseline(s, baseBudget).also {
                                    if (!cancelled() && !stopped) without[s.hero.id] = it
                                }
                            val forced =
                                Setup(
                                    s.hero,
                                    s.game,
                                    s.cfg,
                                    req,
                                    s.box,
                                    s.content,
                                    s.canTakeFrom,
                                    s.excluded +
                                        s.box.items
                                            .filter { it.slot == sl }
                                            .map { it.id },
                                )
                            val withNow = SearchRun(forced, 1, extra = listOf(now), startThreshold = w.value ?: Double.NEGATIVE_INFINITY)
                            search(withNow, stepBudget)
                            val c = withNow.builds().firstOrNull()
                            val beats = c != null && (w.value == null || c.value > w.value + MIN_GAIN * w.unit)
                            val top = if (c != null && (w.value == null || c.value > w.value)) c.value else w.value
                            val bar =
                                Base(
                                    s.hero.id,
                                    top,
                                    if (top == c?.value && c != null) c.items.map { it.id } else w.items,
                                    w.timedOut || withNow.engine.timedOut,
                                    unit(s, top),
                                    baseBudget,
                                )
                            val check = Check(s, bar)
                            val step =
                                if (finishes.isEmpty() || bar.unresolved) {
                                    Step(emptyMap(), emptyMap(), emptyList(), stepBudget)
                                } // the step needs a piece the hero's exclusions don't name; its finishes are the ones above
                                else {
                                    check.step(sl, listOf(0), listOf(now), finishes, stepBudget, 1) { cancelled() || stopped }
                                }
                            if (cancelled() || stopped) return@Callable null
                            val hits = step.hits.filterValues { it > bar.bar }
                            val barItems =
                                bar.items
                                    .map { id ->
                                        if (id ==
                                            now.id
                                        ) {
                                            now
                                        } else {
                                            s.box.itemsById[id]
                                        }
                                    }.takeIf { it.size == 6 && it.all { x -> x != null } }
                                    ?.map { it!! }
                            val bestIds = step.best[0] ?: c?.takeIf { beats }?.items?.map { it.id }
                            val bestItems =
                                bestIds
                                    ?.map { id ->
                                        when {
                                            id == now.id -> now
                                            id < 0 -> finishes[simIndex(id)]
                                            else -> s.box.itemsById[id]
                                        }
                                    }?.takeIf { it.all { x -> x != null } }
                                    ?.map { it!! }
                            val wItems = w.items.mapNotNull { s.box.itemsById[it] }.takeIf { it.size == 6 }
                            val compare = if (step.best[0] != null) barItems else wItems
                            val changes =
                                if (compare != null &&
                                    bestItems != null
                                ) {
                                    changes(check, s.build(compare), s.build(bestItems))
                                } else {
                                    ""
                                }

                            // nothing else moving: what the hero wears, with the piece (as it is, or a finish) in its slot
                            fun into(
                                items: List<Item>,
                                q: Item,
                            ) = items.map { if (it.slot == sl) q else it }
                            val wornItems =
                                s
                                    .current()
                                    .values
                                    .toList()
                                    .takeIf { it.size == 6 }
                            val wornValue = wornItems?.let { check.value(it) }?.takeIf { it.isFinite() }
                            val swapNowValue = if (wornItems != null && wornValue != null) check.value(into(wornItems, now)) else null
                            val swapNow =
                                swapNowValue != null && wornValue != null && swapNowValue > wornValue + MIN_GAIN * unit(s, wornValue)
                            val swapRef = if (swapNow) swapNowValue!! else wornValue
                            val swapHits =
                                if (wornItems == null || swapRef == null) {
                                    emptyMap()
                                } else {
                                    finishes.indices.associateWith { j -> check.value(into(wornItems, finishes[j])) }.filterValues {
                                        it >
                                            swapRef + MIN_GAIN * unit(s, swapRef)
                                    }
                                }
                            val usual = swapHits.entries.sortedByDescending { it.value }.let { e -> e.getOrNull(e.size / 2)?.key }
                            val swapBuild = wornItems?.let { b -> into(b, usual?.let { finishes[it] } ?: now) }
                            val swapChanges =
                                if (wornItems != null && swapHits.isNotEmpty()) {
                                    changes(
                                        check,
                                        s.build(into(wornItems, now)),
                                        s.build(into(wornItems, finishes[swapHits.maxBy { it.value }.key])),
                                    )
                                } else {
                                    ""
                                }
                            val wornBase = wornValue?.let { Base(s.hero.id, it, wornItems!!.map { x -> x.id }, false, unit(s, it), 0, it) }
                            PieceResult(
                                s.hero.id,
                                w,
                                bar,
                                beats,
                                hits,
                                step.unknown.size,
                                finishes.size,
                                minimal(hits.keys.map { finishes[it] }),
                                changes,
                                swapHits,
                                minimal(swapHits.keys.map { finishes[it] }),
                                swapChanges,
                                swapNow,
                                swapNowValue,
                                swapBuild,
                                bestItems ?: c?.items,
                                wornBase,
                                swapRef,
                            ).also(done)
                        }
                    },
                ).mapNotNull { it.get() }
        } finally {
            ex.shutdownNow()
        }
    }

    private fun baseline(
        s: Setup,
        budget: Long,
    ): Base {
        val r = SearchRun(s, 1)
        search(r, budget)
        val b = r.builds().firstOrNull()
        return Base(s.hero.id, b?.value, b?.items?.map { it.id }.orEmpty(), r.engine.stopped, unit(s, b?.value), budget, wornValue(s))
    }

    private inner class Check(
        val s: Setup,
        val base: Base,
        resolved: List<Item>? = null,
    ) {
        val req = s.req
        val obj = Objectives.of(req, s.hb)
        val maxs = s.maxs()
        val minIdx = (0 until 8).filter { !req.mins[it].isNaN() }
        val maxIdx = (0 until 8).filter { !maxs[it].isNaN() }

        // the gear-score term of targets/speed goals is far below the minimum gain; for the gear-score goal it is the goal
        val cares = (obj.uses.filter { it < 8 || req.goal == Goal.GEAR_SCORE } + minIdx).distinct()
        val fullAlts = req.setAlts.isNotEmpty() && req.setAlts.all { it.values.sum() >= 6 }
        val altSets = req.setAlts.flatMap { it.keys }.toSet()
        val slope: DoubleArray? =
            if (req.goal == Goal.TARGETS) {
                Objectives.rollSize(s.hb).let { r ->
                    DoubleArray(8) { i -> if (req.targets[i].isNaN()) 0.0 else maxOf(1, req.weights[i]) / r[i] }
                }
            } else {
                null
            }
        val spares =
            HashMap<Pair<Slot, String>, ArrayList<DoubleArray>>().also { m ->
                for (it in s.box.items) {
                    if (s.eligible(
                            it,
                        )
                    ) {
                        m.getOrPut(it.slot to it.set) { ArrayList() }.add(Calc.itemVec(it, s.hb, req.reforged))
                    }
                }
            }

        val baseItems: List<Item>? = resolved ?: base.items.mapNotNull { s.box.itemsById[it] }.takeIf { it.size == 6 && base.value != null }

        /** The search's value of a complete build, or minus infinity when it breaks the setup's rules. */
        fun value(items: List<Item>): Double = buildValue(s, items)

        /** Whether this hero may wear [piece] at all: not another protected hero's, the lock rule, the main, a set its build has room for. */
        fun allowed(piece: Item): Boolean {
            if (piece.id in s.excluded) return false
            if (!req.allowLocked && piece.locked && piece.equippedOn != s.hero.id) return false
            val m = req.mains[piece.slot]
            if (!m.isNullOrEmpty() && piece.mainType !in m) return false
            return !fullAlts || piece.set in altSets
        }

        /**
         * True when no build with [q] can beat the bar. Swapping q for a same-set spare p that meets every minimum q
         * meets and stays under every maximum q stays under gives a build the hero can already make: so with no starting
         * build, q can't make one either; if p is at least as good in every stat that scores, q can't beat it; and on
         * targets q can add at most its surplus over p priced at full weight. Needs the true starting build.
         */
        fun rejects(q: Item): Boolean {
            if (base.timedOut) return false
            val vq = Calc.itemVec(q, s.hb, req.reforged)
            for (p in spares[q.slot to q.set].orEmpty()) {
                if (minIdx.any { p[it] < vq[it] } || maxIdx.any { p[it] > vq[it] }) continue
                if (base.value == null) return true
                if (cares.all { p[it] >= vq[it] }) return true
                if (slope != null) {
                    var gain = 1e-4 * maxOf(0.0, vq[GS] - p[GS])
                    for (i in 0 until 8) gain += slope[i] * maxOf(0.0, vq[i] - p[i])
                    if (gain <= MIN_GAIN * base.unit) return true
                }
            }
            return false
        }

        fun step(
            sl: Slot,
            candidates: List<Int>,
            pieces: List<Item>,
            all: List<Item>,
            budget: Long,
            threads: Int,
            cancelled: () -> Boolean,
        ): Step {
            // nothing else moving: each finish in place of the piece the hero wears in this slot
            val worn =
                s
                    .current()
                    .values
                    .toList()
                    .takeIf { it.size == 6 && base.worn != null }
            val swapBar = base.worn?.let { it + MIN_GAIN * unit(s, it) }
            val swaps = HashMap<Int, Double>()
            val byPiece = LinkedHashMap<Int, List<Item>>()
            for (pi in candidates) {
                if (cancelled()) break
                if (!allowed(pieces[pi])) continue
                val qs = (0 until sims).map { all[pi * sims + it] }
                if (worn != null &&
                    swapBar != null
                ) {
                    for (q in qs) {
                        buildValue(
                            s,
                            worn.map {
                                if (it.slot ==
                                    sl
                                ) {
                                    q
                                } else {
                                    it
                                }
                            },
                        ).takeIf { it > swapBar }?.let { swaps[simIndex(q.id)] = it }
                    }
                }
                qs.filter { !rejects(it) }.takeIf { it.isNotEmpty() }?.let { byPiece[pi] = it }
            }
            if (byPiece.isEmpty()) return Step(emptyMap(), emptyMap(), emptyList(), budget, swaps)
            val inSlot =
                s.box.items
                    .filter { it.slot == sl }
                    .map { it.id }
            val forced = Setup(s.hero, s.game, s.cfg, req, s.box, s.content, s.canTakeFrom, s.excluded + inSlot)
            val t0 = System.nanoTime()
            // screens: one optimistic piece per group of finishes (every stat at its best over the group), with no maximums.
            // Both only raise what a build can reach, so a group whose screen piece can't beat the bar has no finish that can.
            // First a group per piece, then, for pieces that pass, per line that got the most rolls.
            val loose =
                Setup(
                    s.hero,
                    s.game,
                    s.cfg,
                    req.copy().apply {
                        maxs.fill(Double.NaN)
                        capCrit = false
                    },
                    s.box,
                    s.content,
                    s.canTakeFrom,
                    s.excluded + inSlot,
                )

            fun screen(
                groups: List<List<Item>>,
                ms: Long,
            ): List<List<Item>> {
                val r =
                    SearchRun(
                        loose,
                        groups.size,
                        extra = groups.mapIndexed { i, g -> envelope(g, -1L - i) },
                        startThreshold = base.bar,
                        distinctSlot = sl,
                        distinctFirst = true,
                    )
                search(r, ms, threads)
                return (
                    r.builds().map { b ->
                        b.items.first { it.slot == sl }.id
                    } + r.unsettled().map { it.id }
                ).map { groups[(-1L - it).toInt()] }.distinct()
            }
            val byLine =
                screen(byPiece.values.toList(), budget / 3).flatMap { qs ->
                    qs.groupBy { q -> q.subs.maxBy { it.rolls }.type }.values
                }
            val passed = byPiece.filterValues { qs -> byLine.any { it.first() in qs } }.keys
            val open = (if (byLine.size > passed.size) screen(byLine, budget / 3) else byLine).flatten()
            val t1 = System.nanoTime()
            // the finish dropped into the starting build is a build it surely reaches: only better ones are searched for
            val swapped = HashMap<Long, Pair<Double, List<Item>>>()
            if (baseItems != null) {
                for (q in open) {
                    val items = baseItems.map { if (it.slot == sl) q else it }
                    val v = value(items)
                    if (v > base.bar) swapped[q.id] = v to items
                }
            }
            val run =
                SearchRun(
                    forced,
                    open.size.coerceAtLeast(1),
                    extra = open,
                    startThreshold = base.bar,
                    distinctSlot = sl,
                    seeds = swapped.mapValues { it.value.first },
                )
            if (open.isNotEmpty()) search(run, budget - (t1 - t0) / 1_000_000, threads)
            log?.invoke(
                "step ${s.hero.id}:${sl.name} pieces ${byPiece.size} passed ${passed.size} groups ${byLine.size} open ${open.size} seeded ${swapped.size} " +
                    "of ${candidates.size * sims} screen ${(t1 - t0) / 1_000_000} ms finish ${(System.nanoTime() - t1) / 1_000_000} ms ${run.engine.timedOut}",
            )
            val hits = HashMap<Int, Double>()
            val best = HashMap<Int, List<Long>>()
            val bestValue = HashMap<Int, Double>()

            fun hit(
                qid: Long,
                v: Double,
                items: List<Item>,
            ) {
                val si = simIndex(qid)
                if (v <= (hits[si] ?: Double.NEGATIVE_INFINITY)) return
                hits[si] = v
                val pi = si / sims
                if (v > (bestValue[pi] ?: Double.NEGATIVE_INFINITY)) {
                    bestValue[pi] = v
                    best[pi] = items.map { it.id }
                }
            }
            for ((qid, sw) in swapped) hit(qid, sw.first, sw.second)
            for (b in run.builds()) hit(b.items.first { it.slot == sl }.id, b.value, b.items)
            val unknown = run.unsettled().map { simIndex(it.id) }.filter { it !in hits }
            return Step(hits, best, unknown, budget, swaps)
        }
    }

    companion object {
        const val SIMS = 30

        /** How many times the short budget a step that left finishes open gets on its second try, with every thread. */
        const val REST = 8

        /** Smaller gains don't count: one roll is the mean target weight, a speed roll for the speed goal, 1% of the build otherwise. */
        const val MIN_GAIN = 0.05

        /** The size of one roll of gain for this hero, in the goal's own units. */
        fun unit(
            s: Setup,
            v0: Double?,
        ): Double =
            when (s.req.goal) {
                Goal.TARGETS ->
                    (0 until 8)
                        .filter { !s.req.targets[it].isNaN() }
                        .map { maxOf(1, s.req.weights[it]) }
                        .average()
                        .takeIf { it.isFinite() }
                        ?: 1.0
                Goal.SPEED -> Objectives.rollSize(s.hb)[3]
                else -> v0?.let { maxOf(1e-6, kotlin.math.abs(it) * 0.01) } ?: 1.0
            }

        /** [sims] finishes per piece, in piece order; finish i belongs to piece i / sims and has id -1 - i. */
        fun finishes(
            pieces: List<Item>,
            sims: Int,
        ): List<Item> =
            pieces.flatMapIndexed { pi, p ->
                Finish.of(p, sims).mapIndexed {
                        j,
                        q,
                    ->
                    Item(-1L - (pi * sims + j), q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null)
                }
            }

        fun simIndex(id: Long) = (-1L - id).toInt()

        /** The search's value of a complete build for [s], or minus infinity when it breaks the setup's rules. */
        fun buildValue(
            s: Setup,
            items: List<Item>,
        ): Double {
            val req = s.req
            if (req.setAlts.isNotEmpty()) {
                val cnt = items.groupingBy { it.set }.eachCount()
                if (req.setAlts.none { alt -> alt.all { (k, m) -> (cnt[k] ?: 0) >= m } }) return Double.NEGATIVE_INFINITY
            }
            val f = Calc.final(s.hb, items, req.reforged, s.content)
            val maxs = s.maxs()
            for (x in 0 until 8) {
                if (!req.mins[x].isNaN() && f.stats[x] < req.mins[x]) return Double.NEGATIVE_INFINITY
                if (!maxs[x].isNaN() && f.stats[x] > maxs[x]) return Double.NEGATIVE_INFINITY
            }
            return Objectives.of(req, s.hb).eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
        }

        /** The value of what the hero wears, when that is a complete build meeting its setup. */
        fun wornValue(s: Setup): Double? =
            s
                .current()
                .values
                .toList()
                .takeIf { it.size == 6 }
                ?.let { buildValue(s, it) }
                ?.takeIf { it.isFinite() }

        /** Complete sets of a build, e.g. "Speed 4 + Hit 2". */
        fun setsLabel(
            items: Collection<Item>,
            content: e7.gearscan.data.Content,
        ): String =
            e7.gearscan.calc.Calc
                .setCounts(items, content)
                .joinToString(" + ") { (d, n) -> "${d.name} $n" }
                .ifEmpty { "No set" }

        /** The hero's best build with [finish] in its slot, from its own gear and spares, or null when none meets the setup in [ms]. */
        fun rebuild(
            s: Setup,
            finish: Item,
            ms: Long = 3000,
        ): Build? {
            val forced =
                Setup(
                    s.hero,
                    s.game,
                    s.cfg,
                    s.req,
                    s.box,
                    s.content,
                    s.canTakeFrom,
                    s.excluded + finish.id +
                        s.box.items
                            .filter { it.slot == finish.slot }
                            .map { it.id },
                )
            val probe =
                Item(
                    -2_000_000L,
                    finish.slot,
                    finish.set,
                    finish.rank,
                    finish.level,
                    finish.enhance,
                    finish.mainType,
                    finish.mainValue,
                    finish.subs,
                    finish.locked,
                    finish.equippedOn,
                    main15 = finish.main15,
                )
            val r = SearchRun(forced, 1, extra = listOf(probe)).also { it.run(1, ms) }
            return r.builds().firstOrNull()?.let { b -> s.build(b.items.map { if (it.id == probe.id) finish else it }, b.value) }
        }

        private fun pairs(m: Map<Int, Double>) = JSONArray(m.entries.map { (k, v) -> JSONArray().put(k).put(v) })

        private fun pairsOf(a: JSONArray) = (0 until a.length()).associate { a.getJSONArray(it).let { e -> e.getInt(0) to e.getDouble(1) } }

        /** Of [finishes] (one piece's), those no other one undercuts in every line: the weakest ones, at most [n], weakest first. */
        fun minimal(
            finishes: List<Item>,
            n: Int = 3,
        ): List<Item> {
            fun v(
                q: Item,
                t: e7.gearscan.data.GearStat,
            ) = q.subs.firstOrNull { it.type == t }?.value ?: 0.0

            fun atMost(
                a: Item,
                b: Item,
            ) = (a.subs + b.subs).map { it.type }.toSet().all { v(a, it) <= v(b, it) }
            return finishes
                .filter { a -> finishes.none { b -> atMost(b, a) && !atMost(a, b) } }
                .distinctBy { q -> q.subs.map { it.type to it.value } }
                .sortedBy { q -> q.subs.sumOf { it.value / Finish.range(it.type, q.level).last } }
                .take(n)
        }

        /** A piece at least as good in every stat as each of [finishes] (same piece, so same slot, set and main). */
        fun envelope(
            finishes: List<Item>,
            id: Long,
        ): Item {
            val f = finishes.first()
            val subs =
                finishes.flatMap { it.subs }.groupBy { it.type }.map { (t, xs) ->
                    e7.gearscan.data.Substat(t, xs.maxOf { it.value }, xs.maxOf { it.rolls }, false)
                }
            return Item(id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, subs, false, null)
        }

        private fun asPiece(
            f: Item,
            p: Item,
        ) = Item(p.id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, f.subs, p.locked, p.equippedOn, main15 = p.main15)

        private fun longs(a: JSONArray) = (0 until a.length()).map(a::getLong)
    }
}
