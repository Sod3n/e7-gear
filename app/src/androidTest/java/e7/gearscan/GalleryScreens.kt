package e7.gearscan

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.overlay.*
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Review gallery: renders screens on the fixture box in light and dark for tools/gallery.sh. Emulator only; no assertions on layout. */
class GalleryScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "gallery")
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) {
        val node = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() }; node.performTouchInput { click() }; rule.waitForIdle()
    }
    private var dark = false
    private fun shot(name: String) {
        rule.waitForIdle()
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        val bitmap = if (roots == 1) rule.onRoot().captureToImage().asAndroidBitmap() else inst.uiAutomation.takeScreenshot()
        File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun scrollDown(px: Float = 1400f) {
        rule.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom - 10, endY = bottom - 10 - px.coerceAtMost(bottom - 20)) }
        rule.waitForIdle()
    }

    private fun model(): AppModel {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        val content = JSONObject(assets.open("content.json").bufferedReader().use { it.readText() })
        File(ctx.filesDir, "content.json").writeText(content.toString())
        lateinit var m: AppModel
        ui { m = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { m.ready }
        ui { check(m.graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) e7.gearscan.ui.GameImages.base = images.toURI().toString()
        return m
    }

    @Test fun sell() {
        val m = model()
        val only = InstrumentationRegistry.getArguments().getString("themes") ?: "light,dark"
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            val c = Configuration(LocalConfiguration.current).apply { screenWidthDp = 393; screenHeightDp = if (overlay) 260 else 852 }
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1f), LocalConfiguration provides c) {
                Box(Modifier.width(393.dp).then(if (overlay) Modifier.height(260.dp) else Modifier.fillMaxHeight())) {
                    if (overlay) UpgradeOverlay(m.upgrades, { _, _ -> }, {}, {}) else App(m) {}
                }
            }
        }
        dir.mkdirs(); dir.listFiles()?.filter { it.name.startsWith("sell") || it.name.startsWith("gear") }?.forEach { it.delete() }
        ui { m.tab(Screen.Gear) }; shot("gear-00-home-before")
        ui { m.go(Screen.SellFilters); m.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; shot("sell-00-worth-before"); ui { m.sheet = null }
        ui { m.graph.sellReview.find() }
        rule.waitUntil(240_000) { !m.graph.sellReview.busy }
        rule.waitUntil(120_000) { !m.graph.sellReview.curveBusy && m.graph.sellReview.keepCurve != null }
        android.util.Log.i("E7Gallery", "keep curve ${m.graph.sellReview.keepCurve?.pieces} pieces in ${m.graph.sellReview.keepCurve?.millis} ms")
        ui { m.tab(Screen.Gear) }; shot("gear-01-home")
        ui { m.tab(Screen.ScanData) }; shot("gear-02-scan-data")
        ui { m.tab(Screen.ScanResult) }; shot("gear-03-imported")
        ui { m.tab(Screen.Gear); m.go(Screen.SellFilters) }
        for (theme in only.split(",")) {
            dark = theme == "dark"; ui { Themes.set(ctx, dark); overlay = false; m.sheet = null; if (m.screen != Screen.SellFilters) { m.tab(Screen.Gear); m.go(Screen.SellFilters) } }
            tap("Set up"); shot("sell-01-setup")
            ui { m.go(Screen.SellKept) }; ui { m.back() }
            rule.onAllNodesWithText("Worth rolling").onFirst().assertExists()
            tap("Matches"); shot("sell-02-list")
            scrollDown(); shot("sell-03-list-more")
            val first = m.graph.sellReview.result?.matches?.firstOrNull { it.item.id !in m.reviewedIds }
            if (first != null) { ui { m.sheet = Sheet(SheetKind.SELL_DETAILS, first.item.id) }; shot("sell-04-details"); ui { m.sheet = null } }
            ui { m.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; shot("sell-05-worth-rolling")
            rule.onAllNodesWithText("Total gold").onFirst().performClick(); rule.waitForIdle(); shot("sell-05i-explain"); ui { m.confirm = null }
            rule.onNodeWithContentDescription("Keep-rolling chance").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(40f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(40f, 0f)) }; rule.mainClock.advanceTimeBy(300); shot("sell-05e-dragging")
            rule.onNodeWithContentDescription("Keep-rolling chance").performTouchInput { up() }; rule.waitForIdle()
            rule.onAllNodesWithContentDescription("Type a value")[1].performClick(); rule.waitForIdle(); shot("sell-05f-typing")
            ui { m.sheet = null }; ui { m.sheet = Sheet(SheetKind.SELL_WORTH, 0) }
            ui { m.graph.sellReview.edit(m.graph.sellReview.query.copy(rollOn = 10.0)); m.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; shot("sell-05c-why"); ui { m.graph.sellReview.edit(m.graph.sellReview.query.copy(rollOn = 1.0)) }
            ui { m.sheet = Sheet(SheetKind.SELL_INVENTORY, 0) }; shot("sell-05b-gear-to-review")
            ui { m.sheet = Sheet(SheetKind.SELL_SLOTS, 0) }; shot("sell-05g-slots"); ui { m.sheet = Sheet(SheetKind.SELL_MAINS, 0) }; shot("sell-05h-mains")
            ui { m.sheet = Sheet(SheetKind.SELL_SAFEGUARDS, 0) }; shot("sell-05d-safeguards")
            ui { m.askReset("Reset the safeguards?", "Spd 20, Cr 25%, Cd 35% and 40% for every % stat; flat stats off.") {} }; shot("sell-09-confirm"); ui { m.confirm = null }
            if (theme == only.split(",").first()) ui { m.sheet = null; m.graph.sellReview.result?.matches?.take(6)?.forEach { m.graph.marks.markReviewed(it.item.id, keep = true) } }
            ui { m.sheet = null; m.go(Screen.SellKept) }; shot("sell-06-kept"); ui { m.go(Screen.SellFilters) }
            ui { m.graph.sellReview.trackMatches(openOverlay = false) }
            ui { OverlayState.expanded = true; OverlayState.usableHeightDp = 260f; overlay = true }; rule.mainClock.advanceTimeBy(2000); Thread.sleep(2000); rule.mainClock.advanceTimeBy(500); shot("sell-07-overlay")
            rule.onNodeWithContentDescription("More").performClick(); rule.mainClock.advanceTimeBy(500); shot("sell-08-overlay-more")
            rule.onNodeWithContentDescription("More").performClick()
            ui { overlay = false }
        }
    }
}
