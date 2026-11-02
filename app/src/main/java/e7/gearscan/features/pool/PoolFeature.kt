package e7.gearscan.features.pool

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.Box
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.Mod
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.PieceRef
import e7.gearscan.opt.Build
import e7.gearscan.opt.PoolEffort
import e7.gearscan.opt.PoolLosses
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.pricedPool
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.state.WorkScope
import e7.gearscan.state.blocking
import e7.gearscan.ui.Confirmed
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class PoolFeature(g: AppGraph) : Feature(g, "pool") {
    var tiers by saved("tiers", setOf(5, 4, 3), Codecs.intSet)
    var extra by saved("extra", emptySet(), Codecs.longSet)

    /** Heroes left out of this pool run (skipped in setup, or kept as is after review); their gear stays where it is. */
    var skip by saved("skip", emptySet(), Codecs.longSet)
    var losses by saved("losses", PoolLosses.SAFE, Codecs.enum(PoolLosses.entries.toTypedArray()))
    var effort by saved("effort", PoolEffort.BALANCED, Codecs.enum(PoolEffort.entries.toTypedArray()))

    /** How long a pool run takes per hero on this phone, from the last full run; a slow phone's figure until then. */
    var secondsPerHero by saved("secondsPerHero", 3.0, Codecs.double)

    /** The last pool run, waiting until it is equipped or another run replaces it; its history entry's start time. */
    var pending by saved("pending", null, Codecs.nullable(Codecs.long))
    var lastPool by saved("lastPool", null, LAST)
    val run = work<Unit>("Pool run", Weight.Heavy, notice = "Optimizing pool")

    var mode by mutableStateOf(false)
    var state: PoolState? by mutableStateOf(null)

    /** The player's calls on contested pieces for re-runs: piece -> hero that keeps it, piece -> hero that gets it. */
    var keep by mutableStateOf(emptyMap<Long, Long>())
    var give by mutableStateOf(emptyMap<Long, Long>())

    private val heroes get() = g.heroes

    /** Run order: higher tiers first; inside a tier the hero furthest from their build picks first (name breaks ties). */
    fun heroes(): List<OwnedHero> = members().filter { it.id !in skip }

    /** Pieces worn by heroes of the pool run stay where they are; everything else is spare. */
    fun inUse(b: Box): Set<Long> {
        val members = members().map { it.id }.toSet()
        return b.items.filter { it.equippedOn in members }.map { it.id }.toSet()
    }

    /** The run's heroes including those kept as they are. */
    fun members(): List<OwnedHero> =
        g.scan.box?.heroes.orEmpty()
            .filter { (heroes.tier(it.id) in tiers || it.id in extra) && heroes.gameHero(it) != null }
            .sortedWith(
                compareByDescending<OwnedHero> { heroes.tier(it.id) }
                    .thenBy { heroes.matchFor(it.id)?.pct ?: 101 }
                    .thenBy { heroes.heroName(it) },
            )

    fun score(matches: Map<Long, Int?>): Int {
        var sum = 0.0
        var w = 0.0
        for ((id, m) in matches) {
            if (m == null) continue
            val k = heroes.tier(id).toDouble()
            sum += k * m
            w += k
        }
        return if (w == 0.0) 0 else Math.round(sum / w * 100).toInt()
    }

    /** Whether the player wants this trade's pieces to move: their call if they made one, else what the run did. */
    fun tradeMoves(t: Trade): Boolean =
        when {
            t.items.all { give[it] == t.taker } -> true
            t.items.all { keep[it] == t.holder } -> false
            else -> t.made
        }

    fun callTrade(t: Trade, moves: Boolean) {
        keep = keep - t.items.toSet()
        give = give - t.items.toSet()
        if (moves != t.made) {
            if (moves) give = give + t.items.associateWith { t.taker } else keep = keep + t.items.associateWith { t.holder }
        }
        g.scan.touch()
    }

    fun stop() = run.cancel()

    /** A fresh run forgets the player's calls on trades; [keepCalls] re-runs with them. False when another long search is running. */
    fun start(keepCalls: Boolean = false): Boolean {
        val out =
            run.start {
                val scope = this
                val alive = { scope.isActive }
                if (!keepCalls) {
                    keep = emptyMap()
                    give = emptyMap()
                }
                pending = null
                val lastTrades = state?.trades.orEmpty()
                val picked = heroes().filter { heroes.hasBuild(it.id) == true }
                for (h in picked) {
                    if (heroes.config(h.id).request?.targets?.any { !it.isNaN() } != true) {
                        heroes.presets[h.code]?.let { t ->
                            (t.mostUsed.firstOrNull() ?: t.rta.firstOrNull())?.let { heroes.applyPreset(h.id, it, auto = true) }
                        }
                    }
                }
                val ids = picked.map { it.id }
                val before = ids.associateWith { heroes.matchFor(it)?.pct }
                state =
                    PoolState(
                        ids, before, emptyMap(), emptyMap(), null, null, true, System.currentTimeMillis(), 0,
                        setups = ids.associateWith { heroes.setupKey(it) },
                    )
                onCancel { stopped() }
                val inRun = ids.toSet()
                val keepGear = skip.flatMap { worn(it) }.toSet()
                val builds = LinkedHashMap<Long, Build?>()
                val after = LinkedHashMap<Long, Int?>()

                fun publish(round: Int? = null) {
                    val snapB = LinkedHashMap(builds)
                    val snapA = LinkedHashMap(after)
                    g.scope.launch { if (alive()) state = state?.copy(builds = snapB, after = snapA, round = round ?: state?.round ?: 1) }
                    scope.progress(snapB.size, ids.size, (if ((round ?: 1) > 1) "Round $round · " else "") + "${snapB.size} of ${ids.size} heroes")
                }
                try {
                    val trades =
                        blocking("pool") {
                            val pr =
                                pricedPool(
                                    ids, heroes::tier, keepGear,
                                    { id, excluded -> heroes.setup(id, excluded) { h -> h in inRun || heroes.tier(h) < heroes.tier(id) } },
                                    { s, r -> runWatched(s.hero.id, r, s.req.mods, scope) }, losses, effort, { !alive() },
                                    onVisit = { id, round ->
                                        if (id !in builds) builds[id] = null
                                        publish(round)
                                    },
                                    onAccept = { assign ->
                                        changed(assign, builds, after, before)
                                        publish()
                                    },
                                    keep = keep, give = give,
                                )
                            val t0 = System.nanoTime()
                            val result = pr.run()
                            android.util.Log.i(
                                "E7Pool",
                                "pool run: ${ids.size} heroes, %.1f s, ${pr.proposals} proposals, ".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9) +
                                    pr.spent.entries.joinToString { (k, v) -> "$k ${v.first}× %.1f s".format(java.util.Locale.US, v.second) } + "; slowest: " +
                                    pr.heroTime.entries.sortedByDescending { it.value.first }.take(6).joinToString { (h, v) ->
                                        "${heroes.hero(h)?.let(heroes::heroName)} %.1f s".format(java.util.Locale.US, v.first) + if (v.second > 0) " (${v.second} capped)" else ""
                                    },
                            )
                            if (!alive()) return@blocking null
                            ids.forEach { if (it !in builds) builds[it] = null }
                            changed(result, builds, after, before)
                            val wornBy = HashMap<Long, Long>().also { m -> ids.forEach { h -> worn(h).forEach { m[it] = h } } }
                            val made =
                                ids.flatMap { t -> result[t]?.items.orEmpty().mapNotNull { i -> wornBy[i.id]?.takeIf { it != t }?.let { h -> Triple(h, t, i.id) } } }
                                    .groupBy({ it.first to it.second }, { it.third }).map { (k, items) -> Trade(k.second, k.first, items, true) }
                                    .filter { t -> (after[t.holder] ?: 0) < (before[t.holder] ?: 0) }
                            val notMade =
                                pr.refusals(result).groupBy { it.holder to it.taker }
                                    .map { (k, ds) -> Trade(k.second, k.first, ds.map { it.item }, false, ds.first().takerPoints, ds.first().holderPoints, ds.first().moves) }
                            val kept =
                                keep.entries.filter { (item, h) -> notMade.none { item in it.items } && result[h]?.items?.any { it.id == item } == true }
                                    .groupBy({ it.value }, { it.key }).flatMap { (h, items) ->
                                        lastTrades.filter { it.holder == h && it.items.any { i -> i in items } }
                                            .map { Trade(it.taker, h, it.items.filter { i -> i in items }, false) }
                                    }
                            val stuck =
                                give.filterKeys { it in pr.unmoved }.entries.groupBy({ it.value }, { it.key }).flatMap { (t, items) ->
                                    items.groupBy { i -> wornBy[i] }.mapNotNull { (h, its) -> h?.let { Trade(t, it, its, false, stuck = true) } }
                                }
                            made + notMade.filter { n -> stuck.none { s -> s.taker == n.taker && s.holder == n.holder } } + kept + stuck
                        } ?: return@start
                    state = state?.copy(builds = LinkedHashMap(builds), after = LinkedHashMap(after), trades = trades)
                    finish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    g.feedback.failed("pool", e)
                    stopped()
                }
            }
        g.messages.show(out)
        return out !is e7.gearscan.ui.Outcome.Failed
    }

    /** Copies a run's assignment into [builds]: a new build for heroes whose gear changed, null for the rest. */
    private fun changed(assign: Map<Long, Build>, builds: MutableMap<Long, Build?>, after: MutableMap<Long, Int?>, before: Map<Long, Int?>) {
        for ((id, b) in assign) {
            if (id !in builds) continue
            val moved = b.items.any { it.equippedOn != id || it.mod != null }
            builds[id] = if (moved) b else null
            after[id] = if (moved) heroes.matchOf(id, b)?.pct else before[id]
        }
    }

    private fun finish() {
        val done = state?.copy(running = false, current = null, currentBest = null, finishedAt = System.currentTimeMillis()) ?: return
        val score = score(done.ids.associateWith { done.after[it] ?: done.before[it] })
        if (done.ids.isNotEmpty()) secondsPerHero = (done.finishedAt - done.startedAt) / 1000.0 / done.ids.size
        g.history.record(
            HistoryEntry(
                done.startedAt, true, "Whole pool · ${done.ids.size} heroes", "${losses.label} · ${effort.label}",
                score(done.before), score, done.moves,
                done.builds.filterValues { it != null }.mapValues { (_, b) -> listOf(b!!.items.filter { !it.ghost }.map(PieceRef::of)) },
                done.before, done.after,
            ),
        )
        state = done
        pending = done.startedAt
        lastPool = System.currentTimeMillis() to score
        g.store.saveNow()
        g.platform.notifyPoolDone(done.builds.count { it.value != null })
    }

    private fun stopped() {
        state = state?.copy(running = false, current = null, currentBest = null, finishedAt = System.currentTimeMillis())
    }

    private fun runWatched(id: Long, r: SearchRun, mods: Boolean, scope: WorkScope) {
        scope.onCancel { r.cancel() }
        val watch =
            thread(name = "pool-watch") {
                try {
                    while (scope.isActive && !r.engine.done) {
                        Thread.sleep(400)
                        val best = r.builds().firstOrNull()?.let { heroes.matchOf(id, it)?.pct }
                        g.scope.launch { state = state?.copy(current = id, currentBest = best) }
                    }
                } catch (_: InterruptedException) {
                }
            }
        g.scope.launch { state = state?.copy(current = id, currentBest = null) }
        r.run(Runtime.getRuntime().availableProcessors().coerceIn(1, 6), if (mods) 2 * POOL_HERO_MS else POOL_HERO_MS)
        watch.interrupt()
    }

    private fun worn(id: Long): Set<Long> = g.scan.box?.gearOf(id)?.values.orEmpty().map { it.id }.toSet()

    /** Another hero of the finished pool run: its new build next to what it wears, or its setup if the run left it as is. */
    fun open(id: Long) {
        if (state?.builds?.get(id) != null) g.nav.go(Screen.Compare(0, id)) else g.nav.go(Screen.PoolHero(id, setup = false))
    }

    internal fun stateOf(e: HistoryEntry, fromHistory: Boolean): PoolState {
        val ids = e.beforeMatch.keys.filter { heroes.hero(it) != null }.sortedWith(compareByDescending<Long> { heroes.tier(it) }.thenBy { heroes.hero(it)?.let(heroes::heroName) })
        return PoolState(
            ids, e.beforeMatch, ids.associateWith { id -> e.builds[id]?.firstOrNull()?.let { g.history.restoreBuild(id, it) } }, e.afterMatch,
            null, null, false, e.at, e.at, reviewed = e.reviewed, fromHistory = fromHistory,
        )
    }

    /** Brings the pending run back as a live result (equip allowed), from its history entry. */
    fun openPending() {
        val at = pending ?: return
        if (state?.startedAt != at || state?.fromHistory == true) {
            val e =
                g.history.entries.firstOrNull { it.at == at } ?: run {
                    pending = null
                    g.store.saveNow()
                    return
                }
            state = stateOf(e, fromHistory = false)
        }
        g.nav.go(Screen.PoolRun)
    }

    /** Heroes whose setup changed since the shown run used it. */
    fun editedSinceRun(): List<Long> = state?.let { p -> p.setups.filter { (id, k) -> heroes.setupKey(id) != k }.keys.toList() }.orEmpty()

    /** Heroes of the shown pool run whose gear changes, in walk order. */
    fun reviewQueue(): List<Long> = state?.let { p -> p.ids.filter { p.builds[it] != null } }.orEmpty()

    fun clearReviewed(ids: List<Long>, confirmed: Confirmed) {
        ids.forEach { g.heroes.request(it).reviewed = false }
        g.scan.touch()
    }

    fun markReviewed(id: Long) {
        val p = state ?: return
        state = p.copy(reviewed = p.reviewed + id)
        g.history.entries.firstOrNull { it.at == p.startedAt }?.let { g.history.record(it.copy(reviewed = p.reviewed + id)) }
    }

    fun equip(run: PoolState? = state, reforge: List<Long> = emptyList(), mods: List<Pair<Long, Mod>>? = null) {
        val p = run ?: return
        val prev = LinkedHashMap<Long, Long?>()
        for ((id, b) in p.builds) b?.let { heroes.equip(id, it).forEach { (item, owner) -> prev.putIfAbsent(item, owner) } }
        val before = heroes.recordReforges(reforge)
        val modded = heroes.recordMods(mods ?: p.builds.values.filterNotNull().flatMap(heroes::plannedMods))
        if (pending == p.startedAt) {
            pending = null
            g.store.saveNow()
        }
        g.messages.showToast(
            "Equipped ${p.builds.count { it.value != null }} heroes in this app" + heroes.reforgedNote(before) + heroes.moddedNote(modded, planned = mods == null),
            "Undo",
        ) {
            heroes.undoWrites(before + modded)
            heroes.undoEquip(prev)
            pending = p.startedAt
            g.store.saveNow()
        }
    }

    override fun onBoxChanged(old: Box?, new: Box?) {
        state = null
        keep = emptyMap()
        give = emptyMap()
    }

    private companion object {
        val LAST: Codec<Pair<Long, Int>?> =
            Codecs.nullable(
                Codecs.obj<Pair<Long, Int>>({ JSONObject().put("at", it.first).put("score", it.second) }, { it.getLong("at") to it.getInt("score") }),
            )
    }
}
