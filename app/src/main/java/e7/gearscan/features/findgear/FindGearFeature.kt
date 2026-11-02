package e7.gearscan.features.findgear

import e7.gearscan.state.FeatureContext
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.PotentialQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearPotential
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Plan
import e7.gearscan.ui.Confirmed
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

class FindGearFeature(ctx: FeatureContext, private val content: ContentFeature, private val scanFeature: ScanFeature, private val marks: MarksFeature, private val records: RecordsFeature) : Feature(ctx, "findGear") {
    data class Step(val id: Long, val count: Int, val enhance: Int, val main: Double, val subs: List<Pair<GearStat, Double>>)

    data class Inputs(val query: PotentialQuery, val scan: Long, val steps: List<Step>)

    var query by saved("query", PotentialQuery(), Codecs.obj({ it.toJson() }, { PotentialQuery.of(it) }))
        private set

    private val search = freshWork<Inputs, FindGearResult>("Find gear") { Inputs(query, scanFeature.lastScan, recordedSteps()) }

    val result get() = search.result
    val busy get() = search.busy
    val stale get() = search.stale

    private fun recordedSteps(): List<Step> =
        records.recordedSteps.values
            .map { r -> r.item.let { Step(it.id, r.history.size, it.enhance, it.mainValue, it.subs.map { s -> s.type to s.value }) } }
            .sortedBy { it.id }

    fun edit(q: PotentialQuery) {
        search.cancel()
        query = q
    }

    override fun onBoxReplacing() {
        reset()
    }

    fun reset() = search.clear()

    fun resetSearch(confirmed: Confirmed) = edit(PotentialQuery())

    fun find(): Outcome {
        val q = query
        q.error()?.let { return Outcome.Failed(it) }
        val b = scanFeature.box ?: return Outcome.Failed("Scan your gear first.")
        val scan = scanFeature.lastScan
        val cleared = marks.cleared
        val items = b.items.filter { it.id !in cleared }.map { original ->
            val item = records.recordedSteps[original.id]?.item ?: original
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
                ctx.messages.showToast("The search could not finish. Your requirements are saved; tap Find gear to retry.")
                throw e
            }
        }
    }

    fun toTrack(match: FindGearMatch): Plan<Pair<Item, GearPotential.Goal>> {
        val applied = result ?: return Plan.Stop(Outcome.Failed("Find gear first."))
        val scan = scanFeature.lastScan
        val original = scanFeature.box?.itemsById?.get(match.item.id)
        val latest = records.records[match.item.id]?.takeIf { it.scan == scan }?.item ?: original
        if (latest == null || (!UpgradeRecord.supported(latest) && latest.enhance < 15) || latest.id in marks.cleared) {
            return Plan.Stop(Outcome.Failed("This piece changed. Tap Find gear to refresh the list."))
        }
        return Plan.Go((original ?: latest) to applied.query.goal(match.reforgeAllowed))
    }

    fun toTrackAll(): Plan<List<Pair<Item, TrackerFeature.QueueEntry>>> {
        val applied = result ?: return Plan.Stop(Outcome.Failed("Find gear first."))
        val b = scanFeature.box ?: return Plan.Stop(Outcome.Failed("Scan your gear first."))
        if (busy || stale) return Plan.Stop(Outcome.Failed("Update the matches before starting a new list."))
        val cleared = marks.cleared
        val entries = applied.matches.mapNotNull { match ->
            val original = b.itemsById[match.item.id]?.takeIf { it.id !in cleared } ?: return@mapNotNull null
            val set = content.content.set(original.set)
            original to TrackerFeature.QueueEntry(original.id, applied.query.goal(b.canReforge(original.id)), set?.name ?: original.set.removeSuffix("Set"), set?.icon)
        }
        if (entries.isEmpty()) return Plan.Stop(Outcome.Failed("No matching gear remains. Update the search."))
        return Plan.Go(entries)
    }
}
