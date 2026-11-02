package e7.gearscan

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.overlay.*
import e7.gearscan.ui.App
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Offline fixtures, emulator only. Exercises actual tap entry and snapshots; never touches the user's phone. */
class UpgradeScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val shots get() = File(ctx.getExternalFilesDir(null), "upgrade").also { it.mkdirs() }
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { val n = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst(); runCatching { n.performScrollTo() }; n.performTouchInput { click() }; rule.waitForIdle() }
    private fun shoot(name: String) { rule.onRoot().captureToImage().asAndroidBitmap().let { b -> File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }

    @Test fun recordRollAndResume() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        val piece = graph.scan.box!!.items.first { UpgradeRecord.supported(it) && it.rank == "Epic" && it.enhance == 9 && it.id !in graph.marks.cleared }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi(graph, { _, _ -> }, {}, {}) else App(graph) {} }
            }
        }
        ui { graph.tracker.selectPiece(piece.id); graph.nav.go(Screen.Upgrade) }
        shoot("01-picker-411")
        ui { width = 353; font = 1.3f }
        shoot("01-picker-353-big")
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        shoot("02-panel-353-big")
        tap("Record +12")
        shoot("03-editor-353-big")
        val first = piece.subs.first { 4 in e7.gearscan.opt.Finish.range(it.type, piece.level) }
        val firstIndex = piece.subs.indexOf(first)
        tap("${first.type.short}: ${fmtNum(first.value)}")
        repeat(4 - e7.gearscan.opt.Finish.range(first.type, piece.level).first) { rule.onNodeWithContentDescription("Increase value").performClick() }
        tap("Apply total"); tap("Save +12")
        rule.waitUntil(30_000) { graph.tracker.checkRun.signature.isNotEmpty() }
        assertEquals(12, graph.tracker.record!!.item.enhance)
        assertEquals(first.value + 4, graph.tracker.record!!.item.subs[firstIndex].value, 0.0)
        // Data survives a brand-new controller, with no dependence on in-memory overlay state.
        val restored = reopenTracker(graph, ctx.filesDir)
        assertEquals(12, restored.record!!.item.enhance)
        assertEquals(2, restored.record!!.history.size)
        rule.onNodeWithContentDescription("More").performClick(); rule.waitForIdle(); tap("History"); shoot("04-history-353-big")
        tap("Undo last entry")
        assertEquals(9, graph.tracker.record!!.item.enhance)
        rule.waitUntil(30_000) { graph.tracker.checkRun.signature.isNotEmpty() }
        ui { width = 411; font = 1f }
        tap("Record +12"); shoot("03-editor-411")
        ui { graph.tracker.cancelEdit() }
        // Actual local full-build assessment on the restored account, with per-hero checkpoints.
        ui { graph.tracker.checkRun.runCheck() }
        rule.waitUntil(260_000) { !graph.tracker.checkRun.busy }
        assertNull(graph.tracker.error)
        assertTrue(graph.tracker.checkRun.results.isNotEmpty())
        shoot("05-assessment-411")
        ui { width = 353; font = 1.3f }
        shoot("05-assessment-353-big")
        File(shots, "assessment.txt").writeText(graph.tracker.toJson().toString(2))
        // Real system-overlay windows: save a screenshot separately from the Compose preview.
        inst.uiAutomation.executeShellCommand("appops set e7.gearscan SYSTEM_ALERT_WINDOW allow").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        ui { graph.overlay.expanded = true; graph.overlay.appInFront = false; OverlayService.start(ctx) }
        rule.waitUntil(10_000) { graph.overlay.running }
        // the overlay's compositions run on this rule's clock too: let the panel's opening animation finish
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        Thread.sleep(500)
        inst.uiAutomation.takeScreenshot().let { b -> File(shots, "06-system-window.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        ui { OverlayService.stop(ctx); graph.tracker.hide(); graph.overlay.appInFront = true }
    }
}
