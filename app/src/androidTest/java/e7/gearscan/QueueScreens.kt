package e7.gearscan

import e7.gearscan.ui.trackAllMatches
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

/** Full filtered inventory in the tracker, with native-keyboard-free per-step roll bounds. Emulator only. */
class QueueScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { val n = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst(); runCatching { n.performScrollTo() }; n.performTouchInput { click() }; rule.waitForIdle() }
    private fun shot(name: String) {
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        val dir = File(ctx.getExternalFilesDir(null), "queue").also { it.mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun everyMatchCanBeBrowsedRecordedAndResumed() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); graph.nav.go(Screen.GearPotential) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) graph.content.imageBase = images.toURI().toString()
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                Box(Modifier.width(353.dp).fillMaxHeight()) { if (overlay) androidx.compose.runtime.CompositionLocalProvider(e7.gearscan.ui.LocalImageBase provides graph.content.imageBase) { OverlayUi(graph, { _, _ -> }, {}, {}) } else App(graph) {} }
            }
        }
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle(); rule.waitUntil(30_000) { !graph.findGear.busy }
        // Track all takes the pieces with odds; rare-roll pieces stay in their fold
        val matches = graph.findGear.result!!.withOdds
        assertTrue(matches.size > 20)
        rule.onNodeWithText("Track all ${matches.size}").assertExists()
        shot("30-track-all-matches-353-big")
        ui { graph.messages.show(graph.trackAllMatches(openOverlay = false)) }
        val t = graph.tracker
        assertEquals(matches.map { it.item.id }, t.queue.map { it.id })
        assertEquals(matches.size, t.queue.size)
        assertTrue(t.queue.none { graph.records.records[it.id]!!.item.slot == Slot.BOOTS })
        ui { t.show(); graph.overlay.expanded = true; graph.overlay.floating = true; graph.overlay.usableHeightDp = 0f; overlay = true }
        shot("31-queue-first-353-big")
        tap("›"); assertEquals(1, t.queueIndex)
        shot("32-queue-next-353-big")
        ui { t.selectInQueue(25) }
        assertEquals(25, t.queueIndex)
        ui { graph.overlay.floatingPanel = true }
        rule.onNodeWithContentDescription("Back to E7 Gear").performClick(); rule.waitForIdle(); assertFalse(graph.overlay.floatingPanel)
        val helmet = t.queue.indexOfFirst { e -> graph.records.records[e.id]!!.item.let { it.slot == Slot.HELMET && it.rank == "Heroic" && it.enhance == 9 } }
        assertTrue(helmet >= 0)
        ui { t.selectInQueue(helmet) }
        tap("Record +12"); shot("35-heroic-step-bounds-353-big")
        tap("Eff")
        rule.onNodeWithContentDescription("Step 10").assertDoesNotExist()
        shot("36-roll-bounds-353-big")
        repeat(2) { rule.onNodeWithContentDescription("Increase value").performClick() }
        tap("Apply total"); tap("Save +12")
        assertEquals(12, t.record!!.item.enhance)
        assertEquals(6.0, t.record!!.item.subs.last().value, 0.0)
        assertEquals(matches.size, t.queue.size)
        shot("37-recorded-in-queue-353-big")
        tap("›"); tap("‹")
        assertEquals(helmet, t.queueIndex)
        assertEquals(12, t.record!!.item.enhance)
        val restored = reopenTracker(graph, ctx.filesDir)
        assertEquals(t.queue, restored.queue); assertEquals(helmet, restored.queueIndex)
        assertEquals(2, restored.record!!.history.size)
        File(ctx.getExternalFilesDir(null), "queue/receipt.txt").writeText("${matches.size} filtered matches in queue; next/list/page-two/return callback and Heroic +12 recording passed; selected=$helmet; queue/history restored.\n")
        ui { graph.tracker.hide(); graph.overlay.floating = false; graph.overlay.floatingPanel = true }
    }
}
