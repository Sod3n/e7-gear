package e7.gearscan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.ui.App
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The app-wide no-keyboard rule, on old screens as well as the gear tools. Emulator only. */
class TouchScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { val n = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst(); runCatching { n.performScrollTo() }; n.performTouchInput { click() }; rule.waitForIdle() }
    private fun shot(name: String, legacyStats: Boolean = false) {
        if (!legacyStats) rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        val dir = File(ctx.getExternalFilesDir(null), "touch").also { it.mkdirs() }
        (if (rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1) inst.uiAutomation.takeScreenshot() else rule.onRoot().captureToImage().asAndroidBitmap()).let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private fun clipboard(text: String) { ui { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("local audit", text)) } }

    @Test fun oldScreensUseTouchAndExplicitPaste() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); graph.nav.go(Screen.Heroes) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) graph.content.imageBase = images.toURI().toString()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) { Box(Modifier.width(353.dp).fillMaxHeight()) { App(graph) {} } }
        }
        tap("All names"); shot("20-hero-browse-353-big"); tap("A-F")
        shot("21-hero-filtered-353-big")
        val hero = graph.scan.box!!.heroes.first()
        ui { graph.nav.sheet = Sheet(SheetKind.ARTIFACT, hero.id) }
        tap("All names"); tap("G-L"); shot("22-artifact-browse-353-big")
        ui { graph.nav.sheet = Sheet(SheetKind.TARGETS, hero.id) }
        tap("Speed")
        val oldStatFields = rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        assertTrue(oldStatFields.fetchSemanticsNodes().size >= 3)
        oldStatFields[1].performTextReplacement("220")
        rule.waitForIdle()
        assertEquals(220.0, graph.heroes.request(hero.id).targets[Stat.SPD.ordinal], 1e-8)
        shot("23-legacy-stat-input-353-big", legacyStats = true)
        ui { graph.nav.sheet = Sheet(SheetKind.BONUS, hero.id) }
        assertTrue(rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("24-legacy-bonus-input-353-big", legacyStats = true)
        ui { graph.nav.sheet = null; graph.settings.proxyOn = true; graph.nav.go(Screen.ScanData) }
        clipboard("127.0.0.1:10808"); tap("Paste")
        assertEquals("127.0.0.1:10808", graph.settings.proxy)
        shot("25-proxy-paste-353-big")
        ui { graph.nav.go(Screen.Feedback()) }
        clipboard("Local screenshot audit, never sent.")
        tap("Paste"); shot("26-feedback-paste-353-big")
        rule.onAllNodesWithText("Local screenshot audit, never sent.").assertCountEquals(1)
        // Nothing is sent. Reset only the isolated emulator state.
        ui { graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() }) }
    }
}
