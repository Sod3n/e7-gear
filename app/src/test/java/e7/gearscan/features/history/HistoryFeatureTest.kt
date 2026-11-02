package e7.gearscan.features.history

import e7.gearscan.AppGraph
import e7.gearscan.TestContent
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.Mirror
import e7.gearscan.features.pool.PoolState
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File): AppGraph {
        File(dir, "content.json").writeText(TestContent.text)
        return AppGraph(Disk(dir), scope)
    }

    private fun entry(at: Long, pool: Boolean = true) = HistoryEntry(at, pool, "Whole pool · 2 heroes", "Safe · Balanced", 40, 60, 3, emptyMap())

    @Test fun entriesAndReviewedMarksSurviveARestart() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("history").toFile()
        val a = graph(this, dir)
        a.scan.import(SyntheticBox.generate(1).gearText)
        a.history.record(entry(10))
        a.history.record(entry(20, pool = false))
        a.pool.state = PoolState(emptyList(), emptyMap(), emptyMap(), emptyMap(), null, null, false, 10, 10)
        a.pool.markReviewed(77)
        a.disk.flush()

        val b = graph(this, dir)
        b.loadOnce()
        b.scan.adopt()
        assertEquals(listOf(10L, 20L), b.history.entries.map { it.at })
        assertEquals(setOf(77L), b.history.entries.first { it.at == 10L }.reviewed)
        assertTrue(b.history.entries.first { it.at == 20L }.reviewed.isEmpty())
    }

    @Test fun recordReplacesTheSameRunAndKeepsTheNewestFirst() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this, createTempDirectory("history").toFile())
        g.history.record(entry(1))
        g.history.record(entry(2))
        g.history.record(entry(1).copy(reviewed = setOf(3L)))
        assertEquals(listOf(1L, 2L), g.history.entries.map { it.at })
        assertEquals(setOf(3L), g.history.entries.first().reviewed)
    }

    @Test fun eraseEmptiesTheHistory() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this, createTempDirectory("history").toFile())
        g.scan.import(SyntheticBox.generate(1).gearText)
        g.history.record(entry(1))
        g.scan.erase()
        assertTrue(g.history.entries.isEmpty())
    }
}
