package e7.gearscan

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The scan's ask for "Display over other apps": first look, and after coming back with the switch still off.
 * "Scan without it" closes the ask and stops it coming back. Saves shots; emulator only.
 */
class ScanAskScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "scanask")
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun shot(name: String) {
        val b = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun run(widthDp: Int, fontScale: Float, dark: Boolean) {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        dir.mkdirs()
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(JSONObject(assets.open("content.json").bufferedReader().use { it.readText() }).toString())
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui {
            Themes.set(ctx, dark); graph.heroes.presetNetwork = false
            graph.scan.pillSkipped = false; graph.scan.pillBlocked = false
            graph.nav.stack = listOf(Screen.Optimize, Screen.ScanData); graph.nav.sheet = Sheet(SheetKind.SCAN_OVERLAY, 0)
        }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                Box(Modifier.width(widthDp.dp).fillMaxHeight()) { App(graph) {} }
            }
        }
        rule.waitForIdle()
        val tag = "$widthDp-${if (fontScale > 1f) "big" else "std"}-${if (dark) "dark" else "light"}"
        shot("$tag-1-ask")
        ui { graph.scan.pillBlocked = true }
        shot("$tag-2-still-off")
        rule.onNodeWithText("Scan without it").performClick()
        rule.waitForIdle()
        assertTrue(graph.scan.pillSkipped)
        assertEquals(false, graph.nav.sheet?.kind == SheetKind.SCAN_OVERLAY)
    }

    @Test fun narrowBigFont() = run(353, 1.3f, dark = false)

    @Test fun wideDark() = run(411, 1f, dark = true)
}
