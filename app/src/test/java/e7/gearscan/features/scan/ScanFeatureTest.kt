package e7.gearscan.features.scan

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.Mirror
import e7.gearscan.state.Disk
import e7.gearscan.state.Feature
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("scan").toFile()) = AppGraph(Disk(dir), scope)

    private val text = SyntheticBox.generate(1).gearText
    private val box = Box.parse(text)
    private val smaller = Box.parse(SyntheticBox.generate(2).gearText)

    @Test fun importCancelsRunningWorkThenSetsTheBoxAndSavesIt() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val published = mutableListOf<String>()
        g.pool.run.start { CompletableDeferred<Unit>().await(); published += "pool" }
        g.sellReview.curve.start { CompletableDeferred<Unit>().await(); published += "curve" }
        assertTrue(g.work.active.isNotEmpty())
        val rev = g.scan.rev
        g.scan.import(text)
        assertTrue(g.work.active.isEmpty())
        assertTrue(published.isEmpty())
        assertEquals(box.items.size, g.scan.box!!.items.size)
        assertTrue(g.scan.lastScan > 0)
        assertTrue(g.scan.rev > rev)
        g.disk.flush()
        assertEquals(text, g.scan.boxFile.read())
    }

    @Test fun importCallsTheHostAfterTheBoxIsInPlace() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        var seen: Box? = null
        g.scan.host = object : ScanFeature.Host {
            override fun imported(box: Box) { seen = g.scan.box }
        }
        g.scan.import(text)
        assertNotNull(seen)
        assertTrue(seen === g.scan.box)
    }

    @Test fun importTellsEveryFeatureAndMarksForVanishedPiecesGo() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val reachable = AppGraph::class.java.methods
            .filter { Feature::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0 }
            .map { it.invoke(g) as Feature }
        assertTrue(reachable.size > 10)
        assertEquals(reachable.toSet(), g.features.toSet())

        g.scan.import(text)
        val inBox = box.items.map { it.id }
        val gone = -9L
        g.marks.markReviewed(inBox[0], keep = false)
        g.marks.markReviewed(gone, keep = false)
        g.marks.markReviewed(-8L, keep = true)
        g.scan.import(text)
        assertEquals(setOf(inBox[0]), g.marks.cleared)
        assertTrue(g.marks.kept.isEmpty())
    }

    @Test fun aSecondImportKeepsTheDiffAgainstTheFirst() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        assertNotNull(g.scan.lastDiff)
        val first = g.scan.box
        g.scan.import(SyntheticBox.generate(2).gearText)
        assertFalse(first === g.scan.box)
        assertEquals(smaller.items.size, g.scan.box!!.items.size)
    }

    @Test fun anImportThatIsNotAGearFileLeavesTheBoxAlone() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        val before = g.scan.box
        runCatching { g.scan.import("not json") }.let { assertTrue(it.isFailure) }
        assertTrue(before === g.scan.box)
    }
}
