package e7.gearscan.features.sellreview

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.Item
import e7.gearscan.data.Mirror
import e7.gearscan.data.SellQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.SellSearch
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import e7.gearscan.ui.Outcome
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SellReviewFeatureTest {
    @After fun reset() {
        Mirror.active = false
        Dispatchers.resetMain()
    }

    private class Host : SellHost {
        override var records: Map<Long, UpgradeRecord> = emptyMap()
        var settings: List<Any?> = listOf(1)
        override val settingsKey get() = settings
        override val contentVersion = "test"

        override fun poolMembers() = emptySet<Long>()

        override fun heroNames(box: Box) = emptyMap<Long, String>()

        override fun startQueue(rows: List<Pair<Item, SellSearch.Row>>, query: SellQuery, openOverlay: Boolean) {}

        override fun rollOnChanged(rollOn: Double) {}

        override fun touch() {}
    }

    private val tiny = Box.parse(SyntheticBox.generate(2).gearText)
    private val other = Box.parse(SyntheticBox.generate(3).gearText)

    @Test fun staleFlipsOnEachInputOfTheKeyAndOnlyThen() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val g = AppGraph(Disk(createTempDirectory("sell").toFile()), this)
        val host = Host()
        g.sellReview.host = host
        g.scan.replaceBox(tiny)
        g.scan.lastScan = 1
        assertFalse(g.sellReview.stale)
        assertEquals(Outcome.Done, g.sellReview.find())
        withContext(Dispatchers.Default) { while (g.sellReview.busy) Thread.sleep(20) }
        assertNotNull(g.sellReview.result)
        assertFalse(g.sellReview.stale)
        assertTrue(g.sellReview.fresh)

        g.scan.replaceBox(other)
        assertTrue(g.sellReview.stale)
        g.scan.replaceBox(tiny)
        assertFalse(g.sellReview.stale)
        g.scan.lastScan = 2
        assertTrue(g.sellReview.stale)
        g.scan.lastScan = 1
        host.settings = listOf(2)
        assertTrue(g.sellReview.stale)
        host.settings = listOf(1)
        host.records = mapOf(5L to UpgradeRecord(1, listOf(UpgradeRecord.fromScan(tiny.items.first()), UpgradeRecord.fromScan(tiny.items.first()))))
        assertTrue(g.sellReview.stale)
        host.records = mapOf(5L to UpgradeRecord(9, listOf(UpgradeRecord.fromScan(tiny.items.first()), UpgradeRecord.fromScan(tiny.items.first()))))
        assertFalse(g.sellReview.stale)
        host.records = emptyMap()
        assertFalse(g.sellReview.stale)

        g.sellReview.edit(g.sellReview.query.copy(rollOn = 7.0))
        assertFalse(g.sellReview.stale)
        g.sellReview.edit(g.sellReview.query.copy(maximumChance = g.sellReview.query.maximumChance + 1))
        assertTrue(g.sellReview.stale)
    }

    @Test fun aStartWhileAnotherHeavyRunIsRefusedWithItsName() = runTest(UnconfinedTestDispatcher()) {
        val g = AppGraph(Disk(createTempDirectory("sell").toFile()), this)
        g.sellReview.host = Host()
        g.scan.replaceBox(tiny)
        g.pool.run.start { CompletableDeferred<Unit>().await() }
        assertEquals(Outcome.Failed("Finish or stop Pool run first"), g.sellReview.find())
        assertFalse(g.sellReview.busy)
        g.work.cancelAll()
    }

    @Test fun anInvalidQueryIsRefusedWithItsMessage() = runTest(UnconfinedTestDispatcher()) {
        val g = AppGraph(Disk(createTempDirectory("sell").toFile()), this)
        g.sellReview.host = Host()
        g.scan.replaceBox(tiny)
        g.sellReview.edit(g.sellReview.query.copy(maximumChance = 200.0))
        assertEquals(Outcome.Failed("Chance must be between 0 and 100%."), g.sellReview.find())
        assertFalse(g.sellReview.busy)
        g.work.cancelAll()
    }
}
