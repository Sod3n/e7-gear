package e7.gearscan

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The main screens in the light and the dark theme, saved for eyeballing; offline (fixture content + backup). Emulator only. */
class ThemeScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "theme")
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun shot(name: String) {
        val b = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lightAndDark() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        dir.deleteRecursively(); dir.mkdirs()
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(JSONObject(assets.open("content.json").bufferedReader().use { it.readText() }).toString())
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { check(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        rule.setContent { Box(Modifier.width(411.dp).fillMaxHeight()) { App(graph) {} } }
        val hero = graph.scan.box!!.heroes.first { graph.heroes.gameHero(it) != null && graph.scan.box!!.gearOf(it.id).size == 6 }.id
        val screens = listOf("optimize" to Screen.Optimize, "heroes" to Screen.Heroes, "hero" to Screen.HeroDetail(hero),
            "scan-data" to Screen.ScanData, "sell-filters" to Screen.SellFilters,
            "gear-potential" to Screen.GearPotential, "upgrade" to Screen.Upgrade, "overlay-intro" to Screen.OverlayIntro,
            "feedback" to Screen.Feedback())
        for (dark in listOf(false, true)) {
            ui { Themes.set(ctx, dark) }
            for ((name, screen) in screens) {
                ui { graph.nav.go(screen) }
                shot((if (dark) "dark-" else "light-") + name)
            }
        }
    }
}
