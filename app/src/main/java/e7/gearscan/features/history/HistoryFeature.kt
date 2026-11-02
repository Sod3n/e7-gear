package e7.gearscan.features.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.Box
import e7.gearscan.data.History
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.PieceRef
import e7.gearscan.features.optimize.SearchState
import e7.gearscan.opt.Build
import e7.gearscan.opt.Mods
import e7.gearscan.state.Feature

class HistoryFeature(g: AppGraph) : Feature(g, "history") {
    val file = savedFile("history.json", backup = true, path = "history.json")

    var entries: List<HistoryEntry> by mutableStateOf(emptyList())
        private set

    /** Optimize's third switch position: past runs and searches instead of a setup. */
    var shown by mutableStateOf(false)

    fun record(e: HistoryEntry) {
        entries = (listOf(e) + entries.filter { it.at != e.at }).take(History.KEEP)
        file.write(History.encode(entries))
    }

    /** A stored build on today's gear (a mod put back on its piece); null if the hero or a piece is gone. */
    fun restoreBuild(heroId: Long, refs: List<PieceRef>): Build? {
        val b = g.scan.box ?: return null
        val items = refs.map { r -> (b.itemsById[r.id] ?: return null).let { r.mod?.let { m -> Mods.apply(it, m) } ?: it } }
        return g.heroes.setup(heroId)?.build(items)
    }

    /** Opens a past run (the pending pool run and the newest search live); false while a run or search is going, which it would replace. */
    fun openHistory(e: HistoryEntry): Boolean {
        if (e.pool) {
            if (g.pool.state?.running == true) return false
            if (e.at == g.pool.pending) {
                g.pool.openPending()
                return true
            }
            g.pool.state = g.pool.stateOf(e, fromHistory = true)
            g.nav.go(Screen.PoolRun)
        } else {
            val optimize = g.optimize
            if (optimize.search?.running == true) return false
            val id = e.builds.keys.firstOrNull() ?: return false
            val latest = entries.firstOrNull { !it.pool }?.at == e.at
            if (!(latest && optimize.search?.startedAt == e.at && optimize.search?.heroId == id)) {
                val cur = g.heroes.currentBuild(id) ?: return false
                optimize.search =
                    SearchState(
                        id, false, e.at, e.at, e.builds[id].orEmpty().mapNotNull { restoreBuild(id, it) }, cur,
                        historyAt = if (latest) 0 else e.at,
                    )
            }
            g.nav.go(Screen.Results)
        }
        return true
    }

    override fun onBoxChanged(old: Box?, new: Box?) {
        entries = file.read()?.let(History::decode).orEmpty()
    }
}
