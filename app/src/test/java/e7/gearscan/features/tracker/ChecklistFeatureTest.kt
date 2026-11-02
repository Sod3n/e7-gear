package e7.gearscan.features.tracker

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.overlay.OverlayBuild
import e7.gearscan.overlay.OverlayPiece
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
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
class ChecklistFeatureTest {

    private val items = Box.parse(SyntheticBox.generate(1).gearText).items.take(3)

    private fun build() = OverlayBuild("Build", null, items.map { OverlayPiece(it, null, "main", emptyList(), "in bag", false) }, emptyList())

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("checklist").toFile()) = AppGraph(Disk(dir), scope)

    @Test fun finishingRecordsOnceAndMarksTheChecklistRecorded() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        var recorded = 0
        g.checklist.load(build()) { recorded++ }
        assertFalse(g.checklist.recorded)
        g.checklist.finish()
        assertEquals(1, recorded)
        assertTrue(g.checklist.recorded)
    }

    @Test fun tickedStepsGoThroughTheDiskQueueAndSurviveARestart() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("checklist").toFile()
        val first = graph(this, dir)
        var writer: String? = null
        first.disk.beforeRename = { writer = Thread.currentThread().name }
        first.checklist.load(build()) {}
        first.checklist.markCurrentDone()
        first.checklist.markCurrentDone()
        first.disk.flush()
        assertEquals("disk-writer", writer)
        assertEquals(listOf(true, true, false), first.checklist.done.toList())

        val second = graph(this, dir)
        second.checklist.load(build()) {}
        assertEquals(listOf(true, true, false), second.checklist.done.toList())
        assertEquals(1, second.checklist.current - 1)
        second.checklist.undo()
        assertEquals(listOf(true, false, false), second.checklist.done.toList())
    }

    @Test fun aFinishedChecklistForgetsItsProgress() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("checklist").toFile()
        val first = graph(this, dir)
        first.checklist.load(build()) {}
        first.checklist.markCurrentDone()
        first.checklist.finish()
        first.disk.flush()
        val second = graph(this, dir)
        second.checklist.load(build()) {}
        assertEquals(listOf(false, false, false), second.checklist.done.toList())
    }

    @Test fun loadingAChecklistHidesTheTracker() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.tracker.show()
        g.checklist.load(build()) {}
        assertFalse(g.tracker.shown)
        g.checklist.unload()
        assertNull(g.checklist.build)
    }
}
