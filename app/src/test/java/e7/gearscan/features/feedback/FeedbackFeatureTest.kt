package e7.gearscan.features.feedback

import e7.gearscan.AppGraph
import e7.gearscan.data.Mirror
import e7.gearscan.data.Reports
import e7.gearscan.state.Disk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("feedback").toFile()) = AppGraph(Disk(dir), scope)

    @Test fun aCrashFromTheLastRunIsLoadedAndDismissedForGood() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("feedback").toFile()
        File(dir, CRASH_FILE).writeText("IllegalStateException: boom\ntrace")
        val g = graph(this, dir)
        assertNull(g.feedback.lastCrash)
        g.feedback.loadCrash()
        assertEquals("IllegalStateException: boom\ntrace", g.feedback.lastCrash)
        g.feedback.dismissCrash()
        assertNull(g.feedback.lastCrash)
        assertFalse(File(dir, CRASH_FILE).exists())
    }

    @Test fun theDeviceLineCarriesTheLastError() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        assertFalse(g.feedback.deviceInfo().contains("last error"))
        g.feedback.lastError = "scan: server · IOException: reset"
        assertTrue(g.feedback.deviceInfo().endsWith(" · last error: scan: server · IOException: reset"))
    }

    @Test fun theScanLogIsTheLiveCaptureLogElseTheSavedOne() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("feedback").toFile()
        val g = graph(this, dir)
        assertEquals("", g.feedback.scanLog(g.scan.capture.logText()))
        g.scan.capture.log("port 5222 opened")
        g.feedback.saveScanLog(g.scan.capture.logText())
        g.disk.flush()
        assertTrue(g.feedback.scanLog(g.scan.capture.logText()).endsWith("port 5222 opened"))
        val again = graph(this, dir)
        assertEquals("", again.scan.capture.logText())
        assertTrue(again.feedback.scanLog(again.scan.capture.logText()).endsWith("port 5222 opened"))
    }

    @Test fun aReportWithoutAnEndpointStaysQueuedOnDisk() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("feedback").toFile()
        val g = graph(this, dir)
        var outcome: Reports.Outcome? = null
        g.feedback.send(JSONObject().put("id", "AB12").put("text", "hi")) { outcome = it }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { while (outcome == null) kotlinx.coroutines.delay(5) }
        }
        assertEquals(Reports.Outcome.NO_ENDPOINT, outcome)
        assertTrue(File(dir, "reports/AB12.json").exists())
    }

    @Test fun theScanLogIsNotPartOfABackupAndEraseRemovesIt() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.capture.log("x")
        g.feedback.saveScanLog(g.scan.capture.logText())
        g.disk.flush()
        assertTrue(g.feedback.logFile.exists)
        assertFalse(g.feedback.logFile.backup)
        g.store.erase()
        g.disk.flush()
        assertFalse(g.feedback.logFile.exists)
    }
}
