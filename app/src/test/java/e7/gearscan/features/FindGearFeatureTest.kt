package e7.gearscan.features

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Mirror
import e7.gearscan.data.PotentialQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.features.findgear.FindGearFeature
import e7.gearscan.opt.GearPotential
import e7.gearscan.overlay.UpgradeTracker
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import e7.gearscan.ui.Outcome
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindGearFeatureTest {
    private companion object { const val SCAN = 100L }

    @After fun resetMirror() { Mirror.active = false }

    private val synthetic = Box.parse(SyntheticBox.generate(1).gearText)

    private class FakeSource : FindGearFeature.Source {
        override var records: Map<Long, UpgradeRecord> = emptyMap()
        var tracked = 0
        override fun setLabel(key: String) = key to null
        override fun track(item: Item, goal: GearPotential.Goal, openOverlay: Boolean) { startOnly(item); tracked++ }
        override fun trackQueue(entries: List<Pair<Item, UpgradeTracker.QueueEntry>>, openOverlay: Boolean) {
            entries.forEach { startOnly(it.first) }
            tracked += entries.size
        }
        fun startOnly(item: Item) { records = records + (item.id to UpgradeRecord(SCAN, listOf(UpgradeRecord.fromScan(item)))) }
        fun recordStep(item: Item) {
            val sub = item.subs.first()
            val next = Item(
                item.id, item.slot, item.set, item.rank, item.level, item.enhance + 3, item.mainType, item.mainValue,
                listOf(e7.gearscan.data.Substat(sub.type, sub.value + 1, sub.rolls + 1, sub.modified)) + item.subs.drop(1), item.locked, item.equippedOn, main15 = item.main15,
            )
            records = records + (item.id to UpgradeRecord(SCAN, listOf(UpgradeRecord.fromScan(item), next)))
        }
    }

    private fun graph(scope: CoroutineScope, source: FakeSource): AppGraph =
        AppGraph(Disk(createTempDirectory("findgear").toFile()), scope).also {
            it.scan.replaceBox(synthetic)
            it.scan.lastScan = SCAN
            it.findGear.source = source
            it.findGear.edit(PotentialQuery(show = 2, minimumChance = "0"))
        }

    private suspend fun settle(g: AppGraph) = withContext(Dispatchers.Default) {
        withTimeout(60_000) { while (g.findGear.busy) delay(5) }
    }

    private suspend fun found(g: AppGraph) {
        assertEquals(Outcome.Done, g.findGear.find())
        settle(g)
        assertTrue(g.findGear.result!!.matches.isNotEmpty())
    }

    @Test fun staleFlipsOnAQueryChangeANewScanAndARealRecordedStep() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val g = graph(this, source)
        assertFalse(g.findGear.stale)
        found(g)
        assertFalse(g.findGear.stale)

        g.findGear.edit(g.findGear.query.copy(slots = setOf(e7.gearscan.data.Slot.HELMET)))
        assertTrue(g.findGear.stale)
        g.findGear.edit(g.findGear.result!!.query)
        assertFalse(g.findGear.stale)

        g.scan.lastScan = SCAN + 1
        assertTrue(g.findGear.stale)
        g.scan.lastScan = SCAN
        assertFalse(g.findGear.stale)

        source.recordStep(g.findGear.result!!.matches.first { it.item.enhance < 12 }.item)
        assertTrue(g.findGear.stale)
        found(g)
        assertFalse(g.findGear.stale)
    }

    @Test fun trackingAPieceLeavesTheListFresh() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val g = graph(this, source)
        found(g)
        val match = g.findGear.result!!.matches.first { it.item.enhance < 15 }
        assertEquals(Outcome.Done, g.findGear.track(match))
        assertEquals(1, source.tracked)
        assertEquals(1, source.records.size)
        assertFalse(g.findGear.stale)
        assertEquals(Outcome.Done, g.findGear.trackAll(openOverlay = false))
        assertFalse(g.findGear.stale)
    }

    @Test fun trackAllOnAStaleResultFails() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val g = graph(this, source)
        found(g)
        g.findGear.edit(g.findGear.query.copy(minimumChance = "5"))
        assertTrue(g.findGear.stale)
        assertTrue(g.findGear.trackAll() is Outcome.Failed)
        assertEquals(0, source.tracked)
    }

    @Test fun anInvalidQueryFailsWithItsReason() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this, FakeSource())
        g.findGear.edit(PotentialQuery(targets = emptyMap()))
        assertEquals(Outcome.Failed("Add at least one substat minimum."), g.findGear.find())
        assertFalse(g.findGear.busy)
    }

    @Test fun clearedPiecesAreLeftOutOfTheSearch() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this, FakeSource())
        found(g)
        val gone = g.findGear.result!!.matches.first().item.id
        g.marks.markReviewed(gone, keep = false)
        found(g)
        assertTrue(g.findGear.result!!.matches.none { it.item.id == gone })
        assertEquals(GearStat.SPD, g.findGear.query.targets.keys.first())
    }
}
