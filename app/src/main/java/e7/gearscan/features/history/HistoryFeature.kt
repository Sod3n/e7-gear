package e7.gearscan.features.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.data.Box
import e7.gearscan.data.History
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.PieceRef
import e7.gearscan.opt.Build
import e7.gearscan.opt.Mods
import e7.gearscan.state.Feature

class HistoryFeature(ctx: FeatureContext, private val scan: ScanFeature, private val heroes: HeroesFeature) : Feature(ctx, "history") {
    val file = savedFile("history.json", backup = true, path = "history.json")

    var entries: List<HistoryEntry> by mutableStateOf(emptyList())
        private set

    /** Optimize's third switch position: past runs and searches instead of a setup. */
    var shown by mutableStateOf(false)

    fun record(e: HistoryEntry) {
        entries = (listOf(e) + entries.filter { it.at != e.at }).take(History.KEEP)
        file.write(History.encode(entries))
    }

    fun flag(at: Long) = "From history · ${e7.gearscan.ui.historyStamp(at)}. Gear may have moved since; check each piece."

    /** A stored build on today's gear (a mod put back on its piece); null if the hero or a piece is gone. */
    fun restoreBuild(heroId: Long, refs: List<PieceRef>): Build? {
        val b = scan.box ?: return null
        val items = refs.map { r -> (b.itemsById[r.id] ?: return null).let { r.mod?.let { m -> Mods.apply(it, m) } ?: it } }
        return heroes.setup(heroId)?.build(items)
    }

    override fun onBoxChanged(old: Box?, new: Box?) {
        entries = file.read()?.let(History::decode).orEmpty()
    }
}
