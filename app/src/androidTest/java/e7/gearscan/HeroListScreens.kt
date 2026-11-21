package e7.gearscan

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
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
 * The Heroes list's Show switch on the fixture backup: the default view (6-star heroes and heroes wearing gear), every
 * hero, and every hero with tiers 2 and 1 open (the longest list the screen composes). Saves shots and logs how long
 * the longest list takes. Emulator only; needs E7_FIXTURES.
 */
class HeroListScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "herolist")
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
        ui { check(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        ui { Themes.set(ctx, dark); graph.heroes.showAll = false; graph.heroes.presetNetwork = false }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                Box(Modifier.width(widthDp.dp).fillMaxHeight()) { App(graph) {} }
            }
        }
        val box = graph.scan.box!!
        assertTrue("the fixture needs heroes outside the default view", box.heroes.size > box.gearable.size)
        val tag = "$widthDp-${if (fontScale > 1f) "big" else "std"}-${if (dark) "dark" else "light"}"
        ui { graph.nav.go(Screen.Heroes) }
        shot("$tag-1-default")
        rule.onNodeWithText("All ${box.heroes.size}").performClick()
        rule.waitForIdle()
        assertTrue(graph.heroes.showAll)
        shot("$tag-2-all")
        val t0 = SystemClock.elapsedRealtime()
        rule.onAllNodes(hasText("Tiers", substring = true)).onFirst().performClick()
        rule.waitForIdle()
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i("E7HeroList", "$tag: ${box.heroes.size} heroes (${box.gearable.size} by default), all tiers open in $ms ms")
        shot("$tag-3-all-open")
        rule.onNodeWithText("6★ or geared").performClick()
        rule.waitForIdle()
        assertEquals(false, graph.heroes.showAll)
    }

    @Test fun narrowBigFont() = run(353, 1.3f, dark = false)

    @Test fun wideDark() = run(411, 1f, dark = true)
}
