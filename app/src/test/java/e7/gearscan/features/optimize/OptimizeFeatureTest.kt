package e7.gearscan.features.optimize

import e7.gearscan.AppGraph
import e7.gearscan.TestContent
import e7.gearscan.data.Mirror
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("optimize").toFile()): AppGraph {
        File(dir, "content.json").writeText(TestContent.text)
        return AppGraph(Disk(dir), scope)
    }

    @Test fun aSearchCancelledMidWayPublishesNothing() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(SyntheticBox.generate(1).gearText)
        g.optimize.startSearch()
        assertTrue(g.optimize.search!!.running)
        g.optimize.cancelSearch()
        withContext(Dispatchers.Default) { Thread.sleep(1_500) }
        val s = g.optimize.search!!
        assertTrue(s.cancelled)
        assertEquals(false, s.running)
        assertTrue(s.builds.isEmpty())
        assertTrue(g.history.entries.isEmpty())
        assertTrue(g.work.active.isEmpty())
    }

    @Test fun aBoxChangeForgetsTheSearch() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(SyntheticBox.generate(1).gearText)
        g.optimize.search = SearchState(g.heroes.team.first())
        assertNotNull(g.optimize.search)
        g.scan.import(SyntheticBox.generate(2).gearText)
        assertNull(g.optimize.search)
    }
}
