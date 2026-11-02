package e7.gearscan.features.optimize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.calc.Rating
import e7.gearscan.data.Goal
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.PieceRef
import e7.gearscan.data.Request
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtNum
import e7.gearscan.opt.Build
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.state.WorkScope
import e7.gearscan.state.blocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val HISTORY_BUILDS = 10

data class SearchState(
    val heroId: Long,
    val running: Boolean = true,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val builds: List<Build> = emptyList(),
    val current: Build? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val ghostRunning: Boolean = false,
    val ghost: Build? = null,
    val ghostSearched: Boolean = false,
    val team: List<Pair<Long, Build?>> = emptyList(),
    val moved: Int = 0,
    /** Opened from history (its start time): no equip in the app, only a checklist flagged as from history. */
    val historyAt: Long = 0,
    /** No build: the rules that each, loosened alone, let builds through. */
    val blockers: List<String>? = null,
    val checkingBlockers: Boolean = false,
    val limitMs: Long = 15_000L,
)

class OptimizeFeature(g: AppGraph) : Feature(g, "optimize") {
    val limitMs = 15_000L
    val longLimitMs = 60_000L

    /** With the "closest to targets" goal, results show match % unless another rating is picked. */
    var showMatch by mutableStateOf(true)

    var search: SearchState? by mutableStateOf(null)

    private val searchWork = work<Unit>("Search", Weight.Light)

    fun startSearch(limit: Long = limitMs) {
        cancelSearch()
        val id = g.heroes.team.firstOrNull() ?: return
        val setup = g.heroes.setup(id) ?: return
        val cur = setup.build(setup.current().values.toList())
        g.settings.showRating =
            when (setup.req.goal) {
                Goal.EHP -> Rating.EHP
                Goal.SPEED -> Rating.SPD
                Goal.CP -> Rating.CP
                Goal.GEAR_SCORE -> Rating.GS
                else -> if (g.settings.showRating in listOf(Rating.EHP, Rating.SPD, Rating.CP, Rating.GS)) Rating.DMG else g.settings.showRating
            }
        val r = SearchRun(setup, 50)
        search = SearchState(id, current = cur, limitMs = limit)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        g.messages.show(
            searchWork.start {
                onCancel {
                    r.cancel()
                    searchStopped()
                }
                launch {
                    while (search?.running == true) {
                        delay(300)
                        if (search?.running != true) break
                        val partial = withContext(Dispatchers.Default) { r.builds() }
                        if (search?.running == true) {
                            search = search?.let { SearchState(id, true, it.startedAt, builds = partial, current = cur, limitMs = it.limitMs) }
                        }
                    }
                }
                try {
                    val builds =
                        blocking("search") {
                            val t0 = System.nanoTime()
                            r.run(threads, limit)
                            r.builds().also { b ->
                                android.util.Log.i(
                                    "E7Search",
                                    "threads=$threads ms=${(System.nanoTime() - t0) / 1_000_000} nodes=${r.engine.nodes.get()} " +
                                        "timedOut=${r.engine.timedOut} kept=${r.engine.keptPerSlot.toList()} best=${b.firstOrNull()?.value}",
                                )
                            }
                        }
                    val stopped = r.engine.cancelled
                    val started = search?.startedAt ?: return@start
                    if (builds.isNotEmpty() && !stopped) {
                        val best = builds.first()
                        g.history.record(
                            HistoryEntry(
                                started, false, g.heroes.hero(id)?.let(g.heroes::heroName) ?: "Hero",
                                setup.req.goal.short + (setup.req.presetLabel?.let { " · " + it.substringAfter(" · ") } ?: ""),
                                g.heroes.matchOf(id, cur)?.pct ?: 0, g.heroes.matchOf(id, best)?.pct ?: 0,
                                best.items.count { !it.ghost && (it.equippedOn != id || it.mod != null) },
                                mapOf(id to builds.take(HISTORY_BUILDS).map { b -> b.items.filter { !it.ghost }.map(PieceRef::of) }),
                            ),
                        )
                    }
                    val none = builds.isEmpty() && !stopped
                    search =
                        SearchState(
                            id, false, started, System.currentTimeMillis(), builds, cur, r.engine.timedOut, stopped,
                            ghostRunning = setup.req.ghost && !stopped, checkingBlockers = none, limitMs = limit,
                        )
                    if (none) {
                        rescue(setup, r)
                    } else if (setup.req.ghost && !stopped) {
                        ghostSearch(setup, builds.first(), r)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    g.feedback.failed("search", e)
                    searchStopped()
                }
            },
        )
    }

    /** Opens a past search (the newest live); false while a search is going, which it would replace. */
    fun openFromHistory(e: HistoryEntry): Boolean {
        if (search?.running == true) return false
        val id = e.builds.keys.firstOrNull() ?: return false
        val latest = g.history.entries.firstOrNull { !it.pool }?.at == e.at
        if (!(latest && search?.startedAt == e.at && search?.heroId == id)) {
            val cur = g.heroes.currentBuild(id) ?: return false
            search =
                SearchState(
                    id, false, e.at, e.at, e.builds[id].orEmpty().mapNotNull { g.history.restoreBuild(id, it) }, cur,
                    historyAt = if (latest) 0 else e.at,
                )
        }
        g.nav.go(Screen.Results)
        return true
    }

    fun cancelSearch() = searchWork.cancel()

    private fun searchStopped() {
        search =
            search?.let {
                if (it.running) {
                    SearchState(it.heroId, false, it.startedAt, System.currentTimeMillis(), it.builds, it.current, cancelled = true, team = it.team)
                } else {
                    it.copy(ghostRunning = false, checkingBlockers = false)
                }
            }
    }

    /** [best] null: no build meets the rules, so any build with one ghost piece that meets them counts. */
    private suspend fun WorkScope.ghostSearch(setup: Setup, best: Build?, main: SearchRun) {
        val b =
            best?.let { base ->
                blocking("ghost") {
                    val floor = if (main.builds().isEmpty()) Double.NEGATIVE_INFINITY else base.value
                    val ghosts = Ghost.candidates(setup, base, setup.req.reforged, floor)
                    val gs = SearchRun(setup, 1, ghosts, maxGhost = 1, startThreshold = floor)
                    onCancel { gs.cancel() }
                    gs.run(Runtime.getRuntime().availableProcessors().coerceIn(1, 6), 10_000)
                    gs.builds().firstOrNull()?.takeIf { it.ghost != null }
                }
            }
        search = search?.copy(ghostRunning = false, ghost = b, ghostSearched = true)
    }

    /** No build met the rules: find which rule alone rules out every build, then try one ghost piece. */
    private suspend fun WorkScope.rescue(setup: Setup, main: SearchRun) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

        fun searchOf(q: Request) =
            SearchRun(Setup(setup.hero, setup.game, setup.cfg, q, setup.box, setup.content, setup.canTakeFrom, setup.excluded), 1)
                .also { r -> onCancel { r.cancel() } }
        val blockers =
            blocking("rescue") {
                val found = ArrayList<String>()
                for ((label, relax) in relaxations(setup.req)) {
                    if (!isActive) break
                    if (searchOf(setup.req.copy().also(relax)).anyBuild(threads, 3_000) == true) found += label
                }
                found
            }
        search = search?.copy(blockers = blockers, checkingBlockers = false)
        if (!setup.req.ghost) return
        val base =
            blocking("rescue") {
                searchOf(setup.req.copy().also { q -> q.mins.fill(Double.NaN); q.maxs.fill(Double.NaN); q.capCrit = false })
                    .also { it.run(threads, 3_000) }.builds().firstOrNull()
            }
        ghostSearch(setup, base, main)
    }

    private fun relaxations(req: Request): List<Pair<String, (Request) -> Unit>> =
        buildList {
            Stat.entries.forEach { st ->
                val i = st.ordinal
                if (!req.mins[i].isNaN()) add("${st.label} at least ${fmtNum(req.mins[i])}" to { q -> q.mins[i] = Double.NaN })
                if (!req.maxs[i].isNaN()) add("${st.label} at most ${fmtNum(req.maxs[i])}" to { q -> q.maxs[i] = Double.NaN })
            }
            if (req.capCrit && req.maxs[Stat.CR.ordinal].isNaN()) add("Crit chance cap" to { q -> q.capCrit = false })
            if (req.setAlts.isNotEmpty()) add("The sets" to { q -> q.setAlts = emptyList() })
            if (req.mains.values.any { it.isNotEmpty() }) add("The mains" to { q -> q.mains = emptyMap() })
            if (req.minEnhance > 0) add("Enhanced to +${req.minEnhance}" to { q -> q.minEnhance = 0 })
            if (req.lowerPriorityOnly) add("Only lower tiers" to { q -> q.lowerPriorityOnly = false })
            if (!req.allowLocked) add("No locked items" to { q -> q.allowLocked = true })
            if (req.keepCurrent) add("Keep current gear" to { q -> q.keepCurrent = false })
            if (req.maxChanges < 6) add("Change at most ${req.maxChanges}" to { q -> q.maxChanges = 6 })
        }

    fun searchContext(): JSONObject? {
        val s = search ?: return null
        val h = g.heroes.hero(s.heroId)
        return JSONObject().put("hero", h?.let(g.heroes::heroName)).put("request", g.heroes.request(s.heroId).toJson())
            .put("builds", s.builds.size).put("timedOut", s.timedOut).put("cancelled", s.cancelled)
            .put("ghost", s.ghost != null).put("team", s.team.size)
    }

    override fun onBoxChanged(old: e7.gearscan.data.Box?, new: e7.gearscan.data.Box?) {
        search = null
    }
}
