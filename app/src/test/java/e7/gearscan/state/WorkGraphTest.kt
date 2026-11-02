package e7.gearscan.state

import e7.gearscan.AppGraph
import e7.gearscan.ui.Outcome
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkGraphTest {

    private fun graph(scope: kotlinx.coroutines.CoroutineScope) = AppGraph(Disk(createTempDirectory("graph").toFile()), scope)

    @Test fun aHeavyStartWhileAnotherRunsIsRefusedWithItsName() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.pool.run.start { CompletableDeferred<Unit>().await() }
        assertEquals(Outcome.Failed("Finish or stop Pool run first"), g.sellReview.run.start { })
        assertEquals(Outcome.Failed("Finish or stop Pool run first"), g.improveHeroes.run.start { })
        assertFalse(g.sellReview.run.busy || g.improveHeroes.run.busy)
        g.work.cancelAll()
    }

    @Test fun theTrackerCheckYieldsToAHeavyStartAndIsRefusedWhileOneRuns() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.tracker.check.start { CompletableDeferred<Unit>().await() }
        assertTrue(g.tracker.check.busy)
        g.sellReview.run.start { CompletableDeferred<Unit>().await() }
        assertFalse(g.tracker.check.busy)
        assertEquals(Outcome.Failed("Finish or stop Sell review first"), g.tracker.check.start { })
        g.work.cancelAll()
    }

    @Test fun cancelAllBeforeImportRestoreOrEraseLeavesNothingRunning() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val published = mutableListOf<String>()
        fun startAll() {
            g.pool.run.start { CompletableDeferred<Unit>().await(); published += "pool" }
            g.sellReview.curve.start { CompletableDeferred<Unit>().await(); published += "curve" }
            g.tracker.check.start { CompletableDeferred<Unit>().await(); published += "check" }
        }
        val backup = JSONObject(g.store.backup().toString())
        for (change in listOf({ g.store.restore(backup) }, { g.store.erase() })) {
            startAll()
            assertTrue(g.work.active.isNotEmpty())
            g.work.cancelAll()
            change()
            assertTrue(g.work.active.isEmpty())
            assertFalse(g.pool.run.busy || g.sellReview.curve.busy || g.tracker.check.busy)
        }
        assertTrue(published.isEmpty())
    }
}
