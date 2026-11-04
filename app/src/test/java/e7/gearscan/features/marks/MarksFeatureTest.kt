package e7.gearscan.features.marks

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import e7.gearscan.ui.Outcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarksFeatureTest {

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("marks").toFile()) = AppGraph(Disk(dir), scope)

    private val box = Box.parse(SyntheticBox.generate(1).gearText)
    private val ids = box.items.map { it.id }

    @Test fun marksForPiecesNoLongerInTheBoxAreDroppedOnABoxChange() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val (a, b, c, d) = ids
        val gone = -5L
        g.scan.lastScan = 7
        g.marks.markReviewed(a, keep = false)
        g.marks.markReviewed(gone, keep = false)
        g.marks.markReviewed(b, keep = true)
        g.marks.markReviewed(-6L, keep = true)
        g.marks.recordSold(c, 7, keep = false)
        g.marks.recordSold(d, 6, keep = false)
        var notified = 0
        g.marks.onChange = { notified++ }
        g.boxChanged(null, box)
        assertEquals(setOf(a, c, d), g.marks.cleared)
        assertEquals(setOf(b), g.marks.kept)
        assertEquals(listOf(MarksFeature.Sale(c, 7, false)), g.marks.sold)
        assertEquals(1, notified)
        g.boxChanged(box, box)
        assertEquals(1, notified)
    }

    @Test fun aNewScanClearsSoldMarksButKeepsKeepMarks() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(box.toText())
        val (a, b, c) = ids
        g.marks.markReviewed(a, keep = false)
        g.marks.recordSold(b, g.scan.lastScan, keep = false)
        g.marks.markReviewed(c, keep = true)
        // the player did not sell a or b: the new scan still has them, so they come back everywhere
        g.scan.import(box.toText())
        assertEquals(emptySet<Long>(), g.marks.cleared)
        assertEquals(setOf(c), g.marks.kept)
        assertTrue(g.marks.sold.none { !it.kept })
    }

    @Test fun keptMarksSurviveARestart() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("marks").toFile()
        val first = graph(this, dir)
        first.marks.markReviewed(ids[0], keep = true)
        first.marks.markReviewed(ids[1], keep = false)
        first.marks.recordSold(ids[2], 3, keep = true)
        first.disk.flush()
        val second = graph(this, dir)
        second.loadOnce()
        assertEquals(setOf(ids[0], ids[2]), second.marks.kept)
        assertEquals(setOf(ids[1]), second.marks.cleared)
        assertEquals(listOf(MarksFeature.Sale(ids[2], 3, true)), second.marks.sold)
        assertEquals(setOf(ids[0], ids[1], ids[2]), second.marks.reviewed)
    }

    @Test fun markUnmarkAndUndoSale() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        assertTrue(g.marks.markReviewed(1, keep = false) is Outcome.Note)
        assertEquals(Outcome.Done, g.marks.markReviewed(1, keep = true))
        g.marks.recordSold(2, 5, keep = true)
        assertEquals(listOf(MarksFeature.Sale(2, 5, true)), g.marks.sold)
        g.marks.undoSale(MarksFeature.Sale(2, 5, true))
        assertEquals(setOf(1L), g.marks.reviewed)
        g.marks.unmark(1)
        assertTrue(g.marks.reviewed.isEmpty() && g.marks.sold.isEmpty())
    }
}
