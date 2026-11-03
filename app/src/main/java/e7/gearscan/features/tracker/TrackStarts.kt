package e7.gearscan.features.tracker

import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.state.FeatureContext
import e7.gearscan.Screen
import e7.gearscan.data.InventoryOrder
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearPotential
import e7.gearscan.opt.SellSearch
import e7.gearscan.ui.Outcome

class TrackStarts(private val t: TrackerFeature, private val ctx: FeatureContext, private val scan: ScanFeature, private val contentFeature: ContentFeature, private val marks: MarksFeature, private val records: RecordsFeature) {
    // tracking from Track upgrades itself must not stack a second copy, or back would name the screen it is on
    private fun show() { if (ctx.nav.screen != Screen.Upgrade) ctx.nav.go(Screen.Upgrade) }

    fun track(
        item: Item,
        goal: GearPotential.Goal,
        openOverlay: Boolean,
    ): Outcome {
        t.checkRun.stopCheck()
        t.select(item, scan.lastScan)
        t.configure()
        t.useGoal(goal)
        show()
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackQueue(
        entries: List<Pair<Item, TrackerFeature.QueueEntry>>,
        openOverlay: Boolean,
    ): Outcome {
        t.checkRun.stopCheck()
        t.startQueue(entries, scan.lastScan)
        t.configure()
        show()
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackSellQueue(
        rows: List<Pair<Item, SellSearch.Row>>,
        query: SellQuery,
        openOverlay: Boolean,
    ): Outcome {
        val entries =
            rows.map { (original, row) ->
                val set = contentFeature.content.set(original.set)
                original to
                    TrackerFeature.QueueEntry(
                        original.id, null, set?.name ?: original.set.removeSuffix("Set"), set?.icon,
                        row.goal.takeIf { query.scoreFilter }, row.rolls, records.records[original.id]?.takeIf { it.scan == scan.lastScan }?.history?.size ?: 1,
                        row.goal.reforgeAllowed, row.goal.supported,
                    )
            }
        val walked = scan.box?.let { b -> InventoryOrder.walk(entries, { it.first }, b::gameScore) } ?: entries
        t.startQueue(walked, scan.lastScan, query)
        t.configure()
        show()
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackPiece(id: Long): Outcome {
        val original = scan.box?.itemsById?.get(id)?.takeIf { it.id !in marks.cleared && UpgradeRecord.supported(it) } ?: return Outcome.Done
        t.select(original, scan.lastScan)
        t.configure()
        show()
        return t.openOverlay()
    }

    fun trackPieces(ids: List<Long>): Outcome {
        val b = scan.box ?: return Outcome.Done
        val items =
            ids.distinct().mapNotNull { id ->
                val o = b.itemsById[id]?.takeIf { it.id !in marks.cleared && UpgradeRecord.supported(it) } ?: return@mapNotNull null
                val set = contentFeature.content.set(o.set)
                o to TrackerFeature.QueueEntry(o.id, null, set?.name ?: o.set.removeSuffix("Set"), set?.icon)
            }
        if (items.isEmpty()) return Outcome.Done
        t.startQueue(items, scan.lastScan, source = "heroes")
        t.configure()
        show()
        return t.openOverlay()
    }
}
