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
        ui { check(m.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
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
        dir.mkdirs(); dir.listFiles()?.filter { it.name.startsWith("sell") }?.forEach { it.delete() }
        ui { m.go(Screen.SellFilters) }
        ui { m.findSellGear() }
        rule.waitUntil(240_000) { !m.sellBusy }
        for (theme in only.split(",")) {
            dark = theme == "dark"; ui { Themes.set(ctx, dark); overlay = false; m.sheet = null; if (m.screen != Screen.SellFilters) m.go(Screen.SellFilters) }
            tap("Set up"); shot("sell-01-setup")
            tap("Matches"); shot("sell-02-list")
            scrollDown(); shot("sell-03-list-more")
            val first = m.sellResult?.matches?.firstOrNull { it.item.id !in m.reviewedIds }
            if (first != null) { ui { m.sheet = Sheet(SheetKind.SELL_DETAILS, first.item.id) }; shot("sell-04-details"); ui { m.sheet = null } }
            ui { m.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; shot("sell-05-worth-rolling")
            tap("Why 1 in 20 is the best balance"); shot("sell-05c-why")
            ui { m.sheet = Sheet(SheetKind.SELL_INVENTORY, 0) }; shot("sell-05b-gear-to-review")
            if (theme == only.split(",").first()) ui { m.sheet = null; m.sellResult?.matches?.take(6)?.forEach { m.markSellReview(it.item.id, keep = true) } }
            ui { m.sheet = null; m.go(Screen.SellKept) }; shot("sell-06-kept"); ui { m.go(Screen.SellFilters) }
            ui { m.trackSellMatches(openOverlay = false) }
            ui { OverlayState.expanded = true; OverlayState.usableHeightDp = 260f; overlay = true }; rule.mainClock.advanceTimeBy(2000); Thread.sleep(2000); rule.mainClock.advanceTimeBy(500); shot("sell-07-overlay")
            rule.onNodeWithContentDescription("More").performClick(); rule.mainClock.advanceTimeBy(500); shot("sell-08-overlay-more")
            rule.onNodeWithContentDescription("More").performClick()
            ui { overlay = false }
        }
    }
}
