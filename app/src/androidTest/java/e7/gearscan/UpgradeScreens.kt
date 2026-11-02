package e7.gearscan

import android.app.Application
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
        lateinit var model: AppModel
        ui { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { assertTrue(model.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        val piece = model.box!!.items.first { UpgradeRecord.supported(it) && it.rank == "Epic" && it.enhance == 9 && it.id !in model.clearedIds }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
            }
        }
        ui { model.selectUpgrade(piece.id); model.go(Screen.Upgrade) }
        shoot("01-picker-411")
        ui { width = 353; font = 1.3f }
        shoot("01-picker-353-big")
        var checks = 0
        ui { model.upgrades.requestCheck = { checks++ }; OverlayState.upgrade = model.upgrades; OverlayState.expanded = true; overlay = true }
        shoot("02-panel-353-big")
        tap("Record +12")
        shoot("03-editor-353-big")
        val first = piece.subs.first { 4 in e7.gearscan.opt.Finish.range(it.type, piece.level) }
        val firstIndex = piece.subs.indexOf(first)
        tap("${first.type.short}: ${fmtNum(first.value)}")
        repeat(4 - e7.gearscan.opt.Finish.range(first.type, piece.level).first) { rule.onNodeWithContentDescription("Increase value").performClick() }
        tap("Apply total"); tap("Save +12")
        assertEquals(1, checks)
        assertEquals(12, model.upgrades.record!!.item.enhance)
        assertEquals(first.value + 4, model.upgrades.record!!.item.subs[firstIndex].value, 0.0)
        // Data survives a brand-new controller, with no dependence on in-memory overlay state.
        val restored = UpgradeTracker(File(ctx.filesDir, "upgrades.json"))
        assertEquals(12, restored.record!!.item.enhance)
        assertEquals(2, restored.record!!.history.size)
        rule.onNodeWithContentDescription("More").performClick(); rule.waitForIdle(); tap("History"); shoot("04-history-353-big")
        tap("Undo last entry")
        assertEquals(9, model.upgrades.record!!.item.enhance)
        assertEquals(2, checks)
        ui { width = 411; font = 1f }
        tap("Record +12"); shoot("03-editor-411")
        ui { model.upgrades.editing = false }
        // Actual local full-build assessment on the restored account, with per-hero checkpoints.
        ui { model.upgrades.requestCheck = model::checkUpgrade; model.checkUpgrade() }
        rule.waitUntil(260_000) { !model.upgrades.busy }
        assertNull(model.upgrades.error)
        assertTrue(model.upgrades.results.isNotEmpty())
        shoot("05-assessment-411")
        ui { width = 353; font = 1.3f }
        shoot("05-assessment-353-big")
        File(shots, "assessment.txt").writeText(model.upgrades.toJson().toString(2))
        // Real system-overlay windows: save a screenshot separately from the Compose preview.
        inst.uiAutomation.executeShellCommand("appops set e7.gearscan SYSTEM_ALERT_WINDOW allow").close()
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        ui { OverlayState.expanded = true; OverlayState.appInFront = false; OverlayService.start(ctx) }
        rule.waitUntil(10_000) { OverlayState.running }
        // the overlay's compositions run on this rule's clock too: let the panel's opening animation finish
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        Thread.sleep(500)
        inst.uiAutomation.takeScreenshot().let { b -> File(shots, "06-system-window.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        ui { OverlayService.stop(ctx); OverlayState.upgrade = null; OverlayState.appInFront = true }
    }
}
