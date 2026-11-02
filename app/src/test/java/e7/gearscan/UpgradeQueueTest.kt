package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.GearPotential
import e7.gearscan.features.tracker.TrackerFeature
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpgradeQueueTest {
    @get:Rule val temp = TemporaryFolder()
    private fun piece(id: Long) = Item(id, Slot.HELMET, "SpeedSet", "Epic", 85, 9, GearStat.HP, 2700.0,
        listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false), Substat(GearStat.CR, 4.0, 1, false)), false, null, main15 = 2700.0)
    private fun entry(id: Long, reforge: Boolean = true) = TrackerFeature.QueueEntry(id, GearPotential.Goal(mapOf(GearStat.SPD to 20.0), reforge, reforge), "Speed", "set-speed")
    @Test fun allPiecesGoalsAndSelectedPositionSurviveRestart() {
        val env = TrackerEnv(temp.root); val t = env.t
        t.startQueue(listOf(piece(1) to entry(1), piece(2) to entry(2, false)), 10)
        assertEquals(listOf(1L, 2L), t.queue.map { it.id }); assertEquals(0, t.queueIndex)
        t.select(piece(2), 10, keepQueue = true)
        assertFalse(t.goal!!.reforgeAllowed)
        val restored = env.reopen().t
        assertEquals(t.queue, restored.queue); assertEquals(1, restored.queueIndex)
        assertEquals(t.goal, restored.goal)
    }
    @Test fun historiesRemainIndependentWhenNavigatingOrRefreshingTheList() {
        val t = TrackerEnv(temp.root).t
        val items = listOf(piece(1) to entry(1), piece(2) to entry(2))
        t.startQueue(items, 10)
        assertTrue(t.commit(1944.0, listOf(18.0, 5.0, 4.0, 4.0), null, false))
        t.select(piece(2), 10, keepQueue = true)
        assertEquals(9, t.record!!.item.enhance)
        t.select(piece(1), 10, keepQueue = true)
        assertEquals(12, t.record!!.item.enhance)
        t.startQueue(items, 10)
        assertEquals(12, t.record!!.item.enhance)
        assertEquals(1, t.records.getValue(2).history.size)
    }
    @Test fun choosingOnePieceLeavesBatchModeAndANewScanUsesItsOwnTotals() {
        val t = TrackerEnv(temp.root).t
        t.startQueue(listOf(piece(1) to entry(1)), 10)
        t.select(piece(1), 10)
        assertTrue(t.queue.isEmpty())
        assertTrue(t.commit(1944.0, listOf(18.0, 5.0, 4.0, 4.0), null, false))
        t.startQueue(listOf(piece(1) to entry(1)), 11)
        assertEquals(9, t.record!!.item.enhance); assertEquals(11L, t.record!!.scan)
    }
    private fun mark(env: TrackerEnv, vararg ids: Long) = ids.forEach { env.g.marks.markReviewed(it, keep = false) }
    @Test fun clearedItemsLeaveThePendingListWithoutLosingUndoHistory() {
        val env=TrackerEnv(temp.root);val t=env.t
        val pieces=(1L..3L).map { piece(it) to TrackerFeature.QueueEntry(it,null,"Speed",null) }
        t.startQueue(pieces,10,SellQuery())
        mark(env,2);assertEquals(listOf(1L,3L),t.pendingQueue.map { it.id })
        t.select(piece(3),10,keepQueue=true);assertEquals(1,t.queueIndex)
        mark(env,1,3);assertTrue(t.sellQueueComplete);assertEquals(-1,t.queueIndex)
        assertEquals(3,t.queue.size);assertEquals(3,t.records.size)
        val again=env.reopen();val reopened=again.t;assertEquals(10L,reopened.queueScan);mark(again,1,2,3);assertTrue(reopened.sellQueueComplete)
        again.g.marks.unmark(3);assertFalse(reopened.sellQueueComplete);assertEquals(listOf(3L),reopened.pendingQueue.map { it.id })
        assertEquals(t.records.getValue(3).history.size,reopened.records.getValue(3).history.size)
    }

}
