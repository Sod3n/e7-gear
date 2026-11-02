package e7.gearscan.features.scan

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.Item
import e7.gearscan.data.Mirror
import e7.gearscan.data.SellQuery
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import e7.gearscan.ui.Outcome
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanBoxSwapTest {
    @After fun reset() {
        Mirror.active = false
        Dispatchers.resetMain()
    }

    private val text = SyntheticBox.generate(1).gearText
    private val box = Box.parse(text)
    private val other = Box.parse(SyntheticBox.generate(2).gearText)
    private val pieces: List<Item> = box.items.filter { UpgradeRecord.supported(it) && it.enhance < 15 }.take(3)

    private fun graph(scope: CoroutineScope, dir: java.io.File = createTempDirectory("swap").toFile()) = AppGraph(Disk(dir), scope)

    private fun entry(item: Item) = TrackerFeature.QueueEntry(item.id, null, "Set", null)

    private suspend fun sellFound(g: AppGraph) {
        assertEquals(Outcome.Done, g.sellReview.find())
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (g.sellReview.busy) delay(5) } }
        assertNotNull(g.sellReview.result)
    }

    private suspend fun gearFound(g: AppGraph) {
        assertEquals(Outcome.Done, g.findGear.find())
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (g.findGear.busy) delay(5) } }
        assertNotNull(g.findGear.result)
    }

    private fun sellQueue(g: AppGraph) {
        g.tracker.startQueue(pieces.map { it to entry(it) }, g.scan.lastScan, SellQuery())
    }

    @Test fun adoptClearsTheSellReviewResult() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val g = graph(this)
        g.scan.replaceBox(other)
        g.scan.lastScan = 1
        sellFound(g)
        g.scan.adopt(box)
        assertNull(g.sellReview.result)
        assertNull(g.sellReview.keepCurve)
        assertFalse(g.sellReview.busy)
    }

    @Test fun adoptClearsTheFindGearResult() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val g = graph(this)
        g.scan.replaceBox(box)
        g.scan.lastScan = 1
        gearFound(g)
        g.scan.adopt(other)
        assertNull(g.findGear.result)
    }

    @Test fun adoptSwapsTheBoxAndKeepsTheTeamOnHeroesThatStay() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        g.scan.adopt(other)
        assertTrue(g.scan.box === other)
        assertTrue(g.heroes.team.all { other.hero(it) != null })
    }

    @Test fun adoptDropsMarksOfPiecesTheNewBoxLacksBeforeTheTrackerSyncs() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        sellQueue(g)
        assertEquals(pieces[0].id, g.tracker.selected)
        g.marks.markReviewed(pieces[0].id, keep = false)
        g.scan.adopt(null)
        assertTrue(g.marks.reviewed.isEmpty())
        assertEquals(pieces[0].id, g.tracker.selected)
        assertEquals(pieces.map { it.id }, g.tracker.queue.map { it.id })
    }

    @Test fun adoptMovesTheTrackerOffAPieceTheSellQueueAlreadyClearedAndSaves() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        sellQueue(g)
        g.marks.markReviewed(pieces[0].id, keep = false)
        assertEquals(pieces[0].id, g.tracker.selected)
        g.scan.adopt(box)
        assertEquals(pieces[1].id, g.tracker.selected)
        assertEquals(setOf(pieces[0].id), g.marks.cleared)
    }

    @Test fun adoptReloadsTheImproveHeroesStateForTheNewBox() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        g.scan.adopt(null)
        assertNull(g.improveHeroes.heroUpgrades)
        assertFalse(g.improveHeroes.resumable)
    }

    @Test fun restoreShowsTheBackedUpUpgradeRecordsInTheTrackerOfAFreshGraph() = runTest(UnconfinedTestDispatcher()) {
        val a = graph(this)
        a.scan.import(text)
        sellQueue(a)
        val selected = a.tracker.selected
        val backup = a.scan.backupText()
        val b = graph(this)
        assertTrue(b.scan.restore(backup))
        assertEquals(pieces.map { it.id }.toSet(), b.records.records.keys)
        assertEquals(selected, b.tracker.selected)
        assertEquals(pieces.map { it.id }, b.tracker.queue.map { it.id })
        assertTrue(b.tracker.isSellQueue)
    }

    @Test fun restoreSyncsTheMarksSoTheTrackerLeavesAClearedPiece() = runTest(UnconfinedTestDispatcher()) {
        val a = graph(this)
        a.scan.import(text)
        sellQueue(a)
        a.marks.markReviewed(pieces[0].id, keep = false)
        assertEquals(pieces[0].id, a.tracker.selected)
        val backup = a.scan.backupText()
        val b = graph(this)
        assertTrue(b.scan.restore(backup))
        assertEquals(setOf(pieces[0].id), b.marks.cleared)
        assertEquals(pieces[1].id, b.tracker.selected)
    }

    @Test fun eraseEmptiesWhatTheTrackerHeld() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        sellQueue(g)
        assertTrue(g.records.records.isNotEmpty())
        g.tracker.restore(JSONObject(g.tracker.toJson().toString()).put("signature", "sig"), save = false)
        g.scan.erase(e7.gearscan.userConfirmed())
        g.disk.flush()
        assertTrue(g.records.records.isEmpty())
        assertNull(g.tracker.selected)
        assertTrue(g.tracker.queue.isEmpty())
        assertNull(g.tracker.scoreQuery)
        assertEquals("", g.tracker.signature)
        assertTrue(g.tracker.results.isEmpty())
        assertFalse(g.tracker.shown)
        assertFalse(g.tracker.isSellQueue)
        assertEquals(0L, g.tracker.queueScan)
    }
}
