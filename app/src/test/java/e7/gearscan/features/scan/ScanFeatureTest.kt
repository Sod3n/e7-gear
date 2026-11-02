package e7.gearscan.features.scan

import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.state.Disk
import e7.gearscan.Screen
import e7.gearscan.state.Feature
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanFeatureTest {

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
        val rev = g.rev.value
        g.scan.import(text)
        assertTrue(g.work.active.isEmpty())
        assertTrue(published.isEmpty())
        assertEquals(box.items.size, g.scan.box!!.items.size)
        assertTrue(g.scan.lastScan > 0)
        assertTrue(g.rev.value > rev)
        g.disk.flush()
        assertEquals(text, g.scan.boxFile.read())
    }

    @Test fun importPicksATeamAndDropsTiersOfHeroesThatLeft() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.heroes.team = listOf(-5L)
        g.heroes.tiers = mapOf(-5L to 4)
        g.scan.import(text)
        assertEquals(1, g.heroes.team.size)
        assertNotNull(g.scan.box!!.hero(g.heroes.team.first()))
        assertTrue(g.heroes.tiers.isEmpty())
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

    @Test fun theCaptureSessionBelongsToTheFeatureNotToTheProcess() = runTest(UnconfinedTestDispatcher()) {
        val a = graph(this)
        val b = graph(this)
        assertFalse(a.scan.capture === b.scan.capture)
        val conn = a.scan.capture.open()
        conn.write(CaptureSession.DIR_C2S, byteArrayOf(1, 2), 2)
        conn.write(CaptureSession.DIR_C2S, byteArrayOf(3), 1)
        conn.write(CaptureSession.DIR_S2C, byteArrayOf(0x0a, 0xff.toByte()), 2)
        a.scan.capture.log("port 5222 opened")
        assertEquals(1, a.scan.capture.count())
        assertEquals(5L, a.scan.capture.totalBytes())
        assertEquals(listOf("010203", "0aff"), a.scan.capture.hexSegments())
        assertTrue(a.scan.capture.logText().endsWith("port 5222 opened"))
        assertEquals(0, b.scan.capture.count())
        assertEquals("", b.scan.capture.logText())
        a.scan.capture.clear()
        assertEquals(0, a.scan.capture.count())
        assertFalse(a.scan.capture.vpnRunning)
    }

    private fun backupV1() = javaClass.getResource("/backup_v1.json")!!.readText()

    @Test fun restoringAV1BackupCancelsWorkAndLoadsTheBoxHistoryAndFields() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        g.pool.run.start { CompletableDeferred<Unit>().await() }
        assertTrue(g.scan.restore(backupV1()))
        assertTrue(g.work.active.isEmpty())
        assertEquals("sig", g.tracker.checkRun.signature)
        assertNotNull(g.scan.box)
        assertTrue(JSONObject(backupV1()).getJSONObject("box").similar(JSONObject(g.scan.boxFile.read()!!)))
        assertEquals(1_761_000_000_000L, g.scan.lastScan)
        assertEquals(42, g.scan.decodeSec)
        assertEquals(2, JSONArray(g.history.file.read()!!).length())
        assertTrue(JSONObject(backupV1()).getJSONObject("upgrades").similar(JSONObject(g.tracker.upgrades.read()!!)))
        assertTrue(g.marks.cleared.isEmpty())
    }

    @Test fun aFileThatIsNotABackupIsRefusedAndChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        val before = g.scan.box
        assertFalse(g.scan.restore("not json"))
        assertFalse(g.scan.restore("""{"app":"other","state":{}}"""))
        assertFalse(g.scan.restore("""{"app":"e7gear"}"""))
        assertTrue(before === g.scan.box)
    }

    @Test fun backupTextCarriesTheCurrentBoxAndRestoresIntoAnotherGraph() = runTest(UnconfinedTestDispatcher()) {
        val a = graph(this)
        a.scan.import(text)
        a.scan.decodeSec = 9
        val backup = a.scan.backupText()
        val b = graph(this)
        assertTrue(b.scan.restore(backup))
        assertEquals(box.items.size, b.scan.box!!.items.size)
        assertEquals(9, b.scan.decodeSec)
    }

    @Test fun eraseCancelsWorkClearsTheBoxAndReturnsToTheIntro() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(text)
        g.marks.markReviewed(box.items.first().id, keep = false)
        g.pool.run.start { CompletableDeferred<Unit>().await() }
        g.nav.go(Screen.Heroes)
        g.tracker.restore(JSONObject().put("signature", "sig"), save = false)
        g.scan.erase(e7.gearscan.userConfirmed())
        g.disk.flush()
        assertTrue(g.work.active.isEmpty())
        assertEquals("", g.tracker.checkRun.signature)
        assertNull(g.scan.box)
        assertEquals(0L, g.scan.lastScan)
        assertTrue(g.marks.cleared.isEmpty())
        assertFalse(g.scan.boxFile.exists)
        assertEquals(listOf<Screen>(Screen.ScanIntro), g.nav.stack)
    }
}
