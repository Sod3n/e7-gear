package e7.gearscan.features.tracker

import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearPotential
import e7.gearscan.opt.SellSearch
import e7.gearscan.ui.Outcome

class TrackStarts(private val t: TrackerFeature, private val g: AppGraph) {
    fun track(
        item: Item,
        goal: GearPotential.Goal,
        openOverlay: Boolean,
    ): Outcome {
        t.checkRun.stopCheck()
        t.select(item, g.scan.lastScan)
        t.configure()
        t.useGoal(goal)
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackQueue(
        entries: List<Pair<Item, TrackerFeature.QueueEntry>>,
        openOverlay: Boolean,
    ): Outcome {
        t.checkRun.stopCheck()
        t.startQueue(entries, g.scan.lastScan)
        t.configure()
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackSellQueue(
        rows: List<Pair<Item, SellSearch.Row>>,
        query: SellQuery,
        openOverlay: Boolean,
    ): Outcome {
        val entries =
            rows.map { (original, row) ->
                val set = g.content.content.set(original.set)
                original to
                    TrackerFeature.QueueEntry(
                        original.id, null, set?.name ?: original.set.removeSuffix("Set"), set?.icon,
                        row.goal.takeIf { query.scoreFilter }, row.rolls, g.records.records[original.id]?.takeIf { it.scan == g.scan.lastScan }?.history?.size ?: 1,
                        row.goal.reforgeAllowed, row.goal.supported,
                    )
            }
        t.startQueue(entries, g.scan.lastScan, query)
        t.configure()
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) t.openOverlay() else Outcome.Done
    }

    fun trackPiece(id: Long): Outcome {
        val original = g.scan.box?.itemsById?.get(id)?.takeIf { it.id !in g.marks.cleared && UpgradeRecord.supported(it) } ?: return Outcome.Done
        t.select(original, g.scan.lastScan)
        t.configure()
        g.nav.go(Screen.Upgrade)
        return t.openOverlay()
    }

    fun trackPieces(ids: List<Long>): Outcome {
        val b = g.scan.box ?: return Outcome.Done
        val items =
            ids.distinct().mapNotNull { id ->
                val o = b.itemsById[id]?.takeIf { it.id !in g.marks.cleared && UpgradeRecord.supported(it) } ?: return@mapNotNull null
                val set = g.content.content.set(o.set)
                o to TrackerFeature.QueueEntry(o.id, null, set?.name ?: o.set.removeSuffix("Set"), set?.icon)
            }
        if (items.isEmpty()) return Outcome.Done
        t.startQueue(items, g.scan.lastScan, source = "heroes")
        t.configure()
        g.nav.go(Screen.Upgrade)
        return t.openOverlay()
    }
}
