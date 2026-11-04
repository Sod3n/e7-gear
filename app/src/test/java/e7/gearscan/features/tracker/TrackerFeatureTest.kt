package e7.gearscan.features.tracker

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerFeatureTest {

    private val text = SyntheticBox.generate(1).gearText
    private val box = Box.parse(text)
    private val pieces: List<Item> = box.items.filter { UpgradeRecord.supported(it) && it.enhance < 15 }.take(3)

    private fun graph(scope: CoroutineScope) = AppGraph(Disk(createTempDirectory("tracker").toFile()), scope).also { it.scan.import(text) }

    private fun entry(item: Item) = TrackerFeature.QueueEntry(item.id, null, "Set", null)

    private fun own(id: Long) = Item(
        id, Slot.HELMET, "SpeedSet", "Epic", 85, 9, GearStat.HP, 2700.0,
        listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false), Substat(GearStat.CR, 4.0, 1, false)),
        false, null, main15 = 2700.0,
    )

    private fun step(t: TrackerFeature) = assertTrue(t.commit(1944.0, listOf(18.0, 5.0, 4.0, 4.0), null, false))

    @Test fun recordedStepsIgnoreRecordsThatOnlyStartedFromTheScan() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        t.select(own(1), scan)
        assertTrue(g.records.records.containsKey(1L))
        assertTrue(g.records.recordedSteps.isEmpty())

        t.select(own(2), scan)
        step(t)
        assertEquals(setOf(2L), g.records.recordedSteps.keys)

        t.select(own(2), scan + 1)
        assertTrue(g.records.recordedSteps.isEmpty())
    }

    @Test fun selectingStartingAQueueAndUndoingKeepEachPiecesOwnHistory() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        val own = (1L..3L).map { own(it) }
        t.startQueue(own.map { it to entry(it) }, scan)
        assertEquals(listOf(1L, 2L, 3L), t.queue.map { it.id })
        assertEquals(1L, t.selected)
        assertEquals(0, t.queueIndex)

        t.select(own[1], scan, keepQueue = true)
        assertEquals(1, t.queueIndex)
        step(t)
        assertEquals(2, t.record!!.history.size)
        assertEquals(1, g.records.records.getValue(1).history.size)

        t.undo()
        assertEquals(1, t.record!!.history.size)
        t.select(own[0], scan)
        assertTrue(t.queue.isEmpty())
    }

    @Test fun markingSoldOrKeptFromTheOverlayUpdatesTheSharedMarksAndMovesOn() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        t.startQueue(pieces.map { it to entry(it) }, scan, SellQuery())
        assertTrue(t.canMarkSold)

        t.markSold(pieces[0].id)
        assertEquals(setOf(pieces[0].id), g.marks.cleared)
        assertEquals(pieces[1].id, t.selected)
        assertEquals(listOf(pieces[1].id, pieces[2].id), t.pendingQueue.map { it.id })
        assertTrue(t.canUndoStep)

        t.markSold(pieces[1].id, keep = true)
        assertEquals(setOf(pieces[1].id), g.marks.kept)

        t.undoStep()
        assertFalse(pieces[1].id in g.marks.kept)
        assertEquals(pieces[1].id, t.selected)

        t.markSold(pieces[1].id)
        t.markSold(pieces[2].id)
        assertTrue(t.sellQueueComplete)
    }

    @Test fun undoTakesBackTheNewestStepOfTheWalkWhetherMarkOrEntry() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        val own = pieces.map { own(it.id) }
        t.startQueue(own.map { it to entry(it) }, scan, SellQuery())
        assertFalse(t.canUndoStep)
        step(t)
        t.markSold(own[0].id)
        assertEquals(own[1].id, t.selected)

        t.undoStep()
        assertFalse(own[0].id in g.marks.reviewed)
        assertEquals(own[0].id, t.selected)
        assertEquals(2, t.record!!.history.size)

        t.undoStep()
        assertEquals(1, t.record!!.history.size)
        assertFalse(t.canUndoStep)
    }

    @Test fun undoSkipsAStepAlreadyTakenBackElsewhere() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        val own = pieces.map { own(it.id) }
        t.startQueue(own.map { it to entry(it) }, scan, SellQuery())
        t.markSold(own[0].id)
        step(t)
        t.undo()
        assertTrue(t.canUndoStep)

        t.undoStep()
        assertFalse(own[0].id in g.marks.reviewed)
        assertEquals(1, g.records.records.getValue(own[1].id).history.size)
        assertFalse(t.canUndoStep)
    }

    @Test fun theUndoLogSurvivesARestartAndANewWalkClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        val own = pieces.map { own(it.id) }
        t.startQueue(own.map { it to entry(it) }, scan, SellQuery())
        t.markSold(own[0].id)
        t.reload()
        assertTrue(t.canUndoStep)
        t.startQueue(own.drop(1).map { it to entry(it) }, scan, SellQuery())
        assertFalse(t.canUndoStep)
    }

    @Test fun undoAfterRestoringABackupTakesBackTheBackedUpMark() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        val scan = g.scan.lastScan
        val own = pieces.map { own(it.id) }
        t.startQueue(own.map { it to entry(it) }, scan, SellQuery())
        step(t)
        t.markSold(own[0].id)
        val backup = g.scan.backupText()
        t.undoStep()
        assertFalse(own[0].id in g.marks.reviewed)
        assertTrue(g.scan.restore(backup))
        assertTrue(own[0].id in g.marks.cleared)
        t.undoStep()
        assertFalse(own[0].id in g.marks.reviewed)
        assertEquals(own[0].id, t.selected)
        assertEquals(2, t.record!!.history.size)
    }

    @Test fun aMarkForAnOtherPieceThanTheShownOneIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val t = g.tracker
        t.startQueue(pieces.map { it to entry(it) }, g.scan.lastScan, SellQuery())
        t.markSold(pieces[2].id)
        assertTrue(g.marks.cleared.isEmpty())
        assertNull(t.error)
    }

    @Test fun recordedStepsSurviveARestart() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("tracker").toFile()
        val first = AppGraph(Disk(dir), this)
        first.scan.import(text)
        first.tracker.select(own(7), first.scan.lastScan)
        step(first.tracker)
        first.disk.flush()
        val second = AppGraph(Disk(dir), this)
        assertEquals(7L, second.tracker.selected)
        assertEquals(2, second.tracker.record!!.history.size)
    }
}
