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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Feedback screen's "Already known" card from a saved list, light and dark; emulator only. */
class KnownIssuesScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "known-issues")
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    @Test fun cardFromSavedList() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        dir.deleteRecursively(); dir.mkdirs()
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(JSONObject(assets.open("content.json").bufferedReader().use { it.readText() }).toString())
        File(ctx.filesDir, "known_issues.json").writeText("""{"updated":"2026-11-01","issues":[
            {"id":"gear-import-unreachable","app":"gear","title":"Import sometimes says \"Fribbels' server unreachable\"","detail":"Your capture is kept. We're adding the exact error to reports so we can find the cause.","status":"known","fixed_on":null},
            {"id":"w","app":"gear","title":"Example: being worked on","detail":"","status":"working","fixed_on":null},
            {"id":"f","app":"gear","title":"Example: fixed lately","detail":"Update the app to get the fix.","status":"fixed","fixed_on":"2026-10-30"}]}""")
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        rule.setContent { Box(Modifier.width(411.dp).fillMaxHeight()) { App(graph) {} } }
        for (dark in listOf(false, true)) {
            ui { Themes.set(ctx, dark); graph.nav.go(Screen.Feedback()) }
            Thread.sleep(1500)
            rule.waitForIdle()
            rule.onNodeWithText("Already known").assertExists()
            rule.onNodeWithText("Example: fixed lately").assertDoesNotExist()
            shot((if (dark) "dark" else "light") + "-folded")
            rule.onNodeWithText("Show").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Example: fixed lately").assertExists()
            shot((if (dark) "dark" else "light") + "-open")
            rule.onNodeWithText("Hide").performClick()
        }
    }

    private fun shot(name: String) {
        val b = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
