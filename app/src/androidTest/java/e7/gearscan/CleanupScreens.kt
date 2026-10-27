package e7.gearscan

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.overlay.OverlayUi
import e7.gearscan.ui.App
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Make room on a real account, for review: restores E7_FIXTURES' backup.json (content.json stands in for the download,
 * since the emulator may have no network), runs the check, and saves screenshots of every state at two widths and two
 * font sizes to the app's external files dir under cleanup/. Logs the check's time. Emulator only: it replaces the box.
 */
class CleanupScreens {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "cleanup")
    private lateinit var model: AppModel

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun makeRoom() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("layoutOnly") != "1")
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        check(model.content.heroes.isNotEmpty()) { "no game content" }
        var ok = false
        ui { ok = model.restore(backup); model.consent = true }
        check(ok) { "restore failed" }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
            }
        }
        val setups = listOf(411 to 1f, 353 to 1.3f)
        fun top() = repeat(40) { rule.onRoot().performTouchInput { swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = 80) } }
        fun scrollTo(text: String) = runCatching {
            rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[0].performScrollTo(); rule.waitForIdle() }.isSuccess
        // one shot per setup; [at] scrolls to the part of the screen to show
        fun each(name: String, at: () -> Unit = {}) {
            for ((w, f) in setups) {
                ui { width = w; font = f }
                top(); at()
                shoot("$name-$w-${if (f > 1f) "big" else "1x"}")
            }
        }
        ui { model.go(Screen.ScanResult) }
        each("0-imported")
        ui { model.go(Screen.Cleanup) }
        each("1-intro")
        // stop part way and continue: the second run picks up the saved steps
        ui { model.startCleanup() }
        rule.waitUntil(5 * 60_000) { (model.cleanup?.done ?: 0) > 0 }
        each("2-running")
        rule.waitUntil(30 * 60_000) { model.cleanup.let { it != null && it.total > 0 && it.done * 10 >= it.total * 4 } }
        ui { model.stopCleanup() }
        check(model.cleanupResumable) { "a stopped check can't be continued" }
        each("2b-stopped")
        val t0 = System.currentTimeMillis()
        ui { model.startCleanup() }
        rule.waitUntil(45 * 60_000) { model.cleanup?.running == false }
        val c = model.cleanup!!
        Log.i("CleanupScreens", "resumed check: ${c.picks.size} pieces, ${c.unknown} unknown, ${(System.currentTimeMillis() - t0) / 1000} s")
        File(shots, "result.txt").writeText("pieces ${c.picks.size}, unknown ${c.unknown}, resumed run seconds ${(System.currentTimeMillis() - t0) / 1000}\n" +
            c.picks.joinToString("\n") { "${it.item.id} ${it.item.slot} ${it.item.set} +${it.item.enhance} twin=${it.twin?.id} pair=${it.pair} better=${it.better.map { b -> b.id }}" })
        check(!model.cleanupResumable) { "a finished check still offers to continue" }
        each("3-results-top")
        each("3-results-heroes") { scrollTo("On heroes") }
        each("3-results-unlisted") { scrollTo("Heroes not in the scan") }
        each("3-results-bag") { scrollTo("In the bag") }
        each("3-results-end") { repeat(120) { rule.onRoot().performTouchInput { swipeUp(startY = bottom - 40f, endY = top + 40f, durationMillis = 80) } } }
        ui { model.markCleaned(c.picks.first().item.id, true) }
        each("4-ticked")
        ui { model.loadCleanupChecklist(); e7.gearscan.overlay.OverlayState.expanded = true; overlay = true }
        each("5-checklist")
    }

    /** The same screens from the rules' list alone (no finish check): quick layout passes. */
    @Test
    fun layoutOnly() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("layoutOnly") == "1")
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { model.restore(backup) }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
            }
        }
        val members = model.poolMembers().map { it.id }.toSet()
        val inUse = model.box!!.items.filter { it.equippedOn in members }.map { it.id }.toSet()
        val picks = e7.gearscan.opt.Cleanup.duplicates(model.box!!, inUse) + e7.gearscan.opt.Cleanup.stock(model.box!!, inUse)
        ui { model.cleanup = CleanupState(model.lastScan, picks, running = false, finishedAt = System.currentTimeMillis()); model.go(Screen.Cleanup) }
        for ((w, f) in listOf(411 to 1f, 353 to 1.3f)) {
            ui { width = w; font = f; overlay = false }
            val tag = "$w-${if (f > 1f) "big" else "1x"}"
            // a touch on the reason line reaches its row's tap handler (the text itself has no click action)
            rule.onAllNodesWithText("Same as the one on", substring = true, useUnmergedTree = true)[1].performTouchInput { click() }
            rule.waitForIdle()
            shoot("6-open-copy-$tag")
            rule.onAllNodesWithText("Kept instead", substring = true, useUnmergedTree = true)[0].performScrollTo()
            rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.3f, durationMillis = 400) }
            rule.waitForIdle()
            rule.onAllNodesWithText("Kept instead", substring = true, useUnmergedTree = true)[0].performTouchInput { click() }
            rule.waitForIdle()
            shoot("6-open-ticket-$tag")
            ui { model.loadCleanupChecklist(); e7.gearscan.overlay.OverlayState.expanded = true; overlay = true }
            shoot("7-checklist-$tag")
        }
    }
}
