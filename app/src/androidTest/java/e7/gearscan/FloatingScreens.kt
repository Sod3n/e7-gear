package e7.gearscan

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.ui.App
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The app in the phone's floating window (353 x 571 dp, as Honor's measured): with a tracker or checklist loaded it shows
 * only that panel; collapsing shows the whole app with a Panel chip back. E7_FIXTURES backup; screenshots under floating/.
 */
class FloatingScreens {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "floating")
    private lateinit var graph: AppGraph

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun floatingWindow() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        rule.runOnUiThread { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        var restored = false
        ui { restored = graph.scan.restore(backup); graph.tracker.reset() }
        check(restored) { "restore failed" }
        rule.setContent { Box(Modifier.width(353.dp).height(571.dp)) { App(graph) {} } }

        val piece = graph.scan.box!!.items.first { it.rank == "Epic" && it.enhance == 9 && UpgradeRecord.supported(it) }
        ui { graph.settings.useOverGameNative(true); graph.tracker.selectPiece(piece.id); graph.tracker.show(); graph.overlay.expanded = true
            graph.overlay.floating = true; graph.overlay.floatingPanel = true }
        rule.waitUntil(120_000) { !graph.tracker.busy }
        ui { graph.tracker.runCheck() }
        rule.waitUntil(120_000) { !graph.tracker.busy && graph.tracker.complete }
        shoot("1-tracker")
        rule.onNodeWithContentDescription("Collapse tracker").performClick()
        rule.waitForIdle()
        check(!graph.overlay.floatingPanel) { "collapse didn't show the app" }
        shoot("2-app-with-panel-chip")
        rule.onNodeWithText("‹ Back to the tracker").performClick()
        rule.waitForIdle()
        check(graph.overlay.floatingPanel) { "the Panel chip didn't bring the tracker back" }
        ui { graph.tracker.hide() }
        val hero = graph.improveHeroes.heroes().first { graph.scan.box!!.gearOf(it.id).size == 6 }
        ui { graph.checklist.loadBuild(hero.id, graph.heroes.currentBuild(hero.id)!!, "Floating check"); graph.overlay.expanded = true }
        shoot("3-checklist")
        ui { graph.overGame.closeFloatingPanel() }
        check(graph.checklist.build == null && !graph.tracker.shown) { "close left a panel loaded" }
        shoot("4-closed")
        ui { graph.overlay.floating = false; graph.settings.useOverGameNative(false) }
    }
}
