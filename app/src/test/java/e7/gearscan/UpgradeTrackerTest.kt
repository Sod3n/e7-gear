package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.UpgradeCheck
import e7.gearscan.overlay.UpgradeTracker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpgradeTrackerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun piece() = Item(1, Slot.HELMET, "SpeedSet", "Epic", 85, 9, GearStat.HP, 2700.0,
        listOf(Substat(GearStat.SPD, 3.0, 1, false), Substat(GearStat.CR, 8.0, 2, false), Substat(GearStat.CD, 7.0, 1, false), Substat(GearStat.HP_P, 16.0, 3, false)), false, null, main15 = 2700.0)

    @Test fun recordedEntriesPersistImmediatelyAndNewScansReplaceManualValues() {
        val file = File(temp.root, "upgrades.json")
        val tracker = UpgradeTracker(file)
        tracker.select(piece(), 99)
        assertEquals(1512.0, tracker.record!!.item.mainValue, 0.0)
        assertTrue(tracker.commit(1944.0, listOf(7.0, 8.0, 7.0, 16.0), null, false))
        val restored = UpgradeTracker(file)
        assertEquals(12, restored.record!!.item.enhance)
        assertEquals(2, restored.record!!.history.size)
        restored.undo()
        assertEquals(9, UpgradeTracker(file).record!!.item.enhance)
        tracker.select(piece(), 100)
        assertEquals(1, tracker.record!!.history.size)
        assertEquals(99L, restored.record!!.scan)
        assertEquals(100L, UpgradeTracker(file).record!!.scan)
    }

    @Test fun unfinishedChecksStayUncertainAndRecheckGivesThemMoreTime() {
        val file = File(temp.root, "upgrades.json")
        val tracker = UpgradeTracker(file)
        tracker.select(piece(), 99)
        tracker.begin("same-input", 1)
        tracker.checkpoint(listOf(UpgradeCheck.HeroResult(1, "Hero", emptyList(), false, false, 2, false, null, "")))
        tracker.busy = false
        assertTrue(tracker.title.startsWith("Undecided"))
        tracker.begin("same-input", 1)
        assertEquals(1, tracker.attempt)
        assertEquals(1, UpgradeTracker(file).attempt)
        tracker.begin("changed-input", 1)
        assertEquals(0, tracker.attempt)
        assertTrue(tracker.results.isEmpty())
    }

    @Test fun usefulAlreadyDoesNotMeanContinueUpgrading() {
        val tracker = UpgradeTracker(File(temp.root, "upgrades.json"))
        tracker.select(piece(), 1)
        tracker.begin("same", 1)
        tracker.checkpoint(listOf(UpgradeCheck.HeroResult(1, "Hero", emptyList(), true, false, 0, false, 1.0, "")))
        tracker.busy = false
        assertEquals("Pause · useful as it is", tracker.title)
    }
}
