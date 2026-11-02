package e7.gearscan.features.findgear

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.PotentialQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearPotential
import e7.gearscan.overlay.UpgradeTracker
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.ui.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class FindGearMatch(val item: Item, val outcome: GearPotential.Outcome, val reforgeAllowed: Boolean)

data class FindGearResult(
    val query: PotentialQuery,
    val matches: List<FindGearMatch>,
    val unsupported: Int,
    val seconds: Double,
)

class FindGearFeature(g: AppGraph) : Feature(g, "findGear") {
    interface Source {
        val records: Map<Long, UpgradeRecord>
        fun setLabel(key: String): Pair<String, String?>
        fun track(item: Item, goal: GearPotential.Goal, openOverlay: Boolean)
        fun trackQueue(entries: List<Pair<Item, UpgradeTracker.QueueEntry>>, openOverlay: Boolean)
    }

    object NoSource : Source {
        override val records: Map<Long, UpgradeRecord> = emptyMap()
        override fun setLabel(key: String) = key to null
        override fun track(item: Item, goal: GearPotential.Goal, openOverlay: Boolean) {}
        override fun trackQueue(entries: List<Pair<Item, UpgradeTracker.QueueEntry>>, openOverlay: Boolean) {}
    }

    data class Step(val id: Long, val count: Int, val enhance: Int, val main: Double, val subs: List<Pair<GearStat, Double>>)

    data class Inputs(val query: PotentialQuery, val scan: Long, val steps: List<Step>)

    var source: Source = NoSource

    var query by saved("query", PotentialQuery(), Codecs.obj({ it.toJson() }, { PotentialQuery.of(it) }))
        private set

    private val search = freshWork<Inputs, FindGearResult>("Find gear") { Inputs(query, g.scan.lastScan, recordedSteps()) }

    val result get() = search.result
    val busy get() = search.busy
    val stale get() = search.stale

    private fun recordedSteps(): List<Step> =
        source.records.values
            .filter { it.scan == g.scan.lastScan && it.history.size > 1 }
            .map { r -> r.item.let { Step(it.id, r.history.size, it.enhance, it.mainValue, it.subs.map { s -> s.type to s.value }) } }
            .sortedBy { it.id }

    fun edit(q: PotentialQuery) {
        search.cancel()
        query = q
    }

    fun reset() = search.clear()

    fun find(): Outcome {
        val q = query
        q.error()?.let { return Outcome.Failed(it) }
        val b = g.scan.box ?: return Outcome.Failed("Scan your gear first.")
        val scan = g.scan.lastScan
        val cleared = g.marks.cleared
        val items = b.items.filter { it.id !in cleared }.map { original ->
            val item = source.records[original.id]?.takeIf { it.scan == scan && it.history.size > 1 }?.item ?: original
            item to b.canReforge(item.id)
        }.filter { q.includes(it.first) }
        return search.start { key ->
            try {
                withContext(Dispatchers.Default) {
                    val t0 = System.nanoTime()
                    var unsupported = 0
                    val matches = ArrayList<FindGearMatch>()
                    for ((item, allowed) in items) {
                        currentCoroutineContext().ensureActive()
                        val outcome = GearPotential.forecast(item, key.query.goal(allowed))
                        if (!outcome.supported) unsupported++
                        if (key.query.accepts(outcome)) matches += FindGearMatch(item, outcome, allowed)
                    }
                    FindGearResult(
                        key.query,
                        matches.sortedWith(compareByDescending<FindGearMatch> { it.outcome.chance }.thenByDescending { it.item.enhance }.thenBy { it.item.id }),
                        unsupported,
                        (System.nanoTime() - t0) / 1e9,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                g.messages.showToast("The search could not finish. Your requirements are saved; tap Find gear to retry.")
                throw e
            }
        }
    }

    fun track(match: FindGearMatch, openOverlay: Boolean = false): Outcome {
        val applied = result ?: return Outcome.Failed("Find gear first.")
        val scan = g.scan.lastScan
        val original = g.scan.box?.itemsById?.get(match.item.id)
        val latest = source.records[match.item.id]?.takeIf { it.scan == scan }?.item ?: original
        if (latest == null || (!UpgradeRecord.supported(latest) && latest.enhance < 15) || latest.id in g.marks.cleared) {
            return Outcome.Failed("This piece changed. Tap Find gear to refresh the list.")
        }
        source.track(original ?: latest, applied.query.goal(match.reforgeAllowed), openOverlay)
        return Outcome.Done
    }

    fun trackAll(openOverlay: Boolean = true): Outcome {
        val applied = result ?: return Outcome.Failed("Find gear first.")
        val b = g.scan.box ?: return Outcome.Failed("Scan your gear first.")
        if (busy || stale) return Outcome.Failed("Update the matches before starting a new list.")
        val cleared = g.marks.cleared
        val entries = applied.matches.mapNotNull { match ->
            val original = b.itemsById[match.item.id]?.takeIf { it.id !in cleared } ?: return@mapNotNull null
            val (name, icon) = source.setLabel(original.set)
            original to UpgradeTracker.QueueEntry(original.id, applied.query.goal(b.canReforge(original.id)), name, icon)
        }
        if (entries.isEmpty()) return Outcome.Failed("No matching gear remains. Update the search.")
        source.trackQueue(entries, openOverlay)
        return Outcome.Done
    }
}
