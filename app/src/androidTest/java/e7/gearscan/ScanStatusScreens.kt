package e7.gearscan

import e7.gearscan.features.scan.CaptureSession
import e7.gearscan.features.scan.ScanError
import e7.gearscan.features.scan.ScanPhase
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.ScanStep
import e7.gearscan.overlay.ScanPill
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Every scan status on the capture screen, light and dark, and the pill over a game-like backdrop; emulator only. */
class ScanStatusScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val dir get() = File(ctx.getExternalFilesDir(null), "scan-status")
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun shot(name: String) {
        val b = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "screens/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Puts [kb] of fake game traffic into the capture over [conns] connections, as the proxy would. */
    private fun feed(model: AppModel, conns: Int, kb: Int) {
        model.graph.scan.capture.clear()
        repeat(conns) { i -> model.graph.scan.capture.open().let { c -> if (i == 0 && kb > 0) c.write(CaptureSession.DIR_S2C, ByteArray(kb * 1024), kb * 1024) } }
    }

    private fun waitForPoll() { Thread.sleep(1600); rule.waitForIdle() }

    @Test fun everyStatus() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        File(dir, "screens").apply { deleteRecursively(); mkdirs() }
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(JSONObject(assets.open("content.json").bufferedReader().use { it.readText() }).toString())
        lateinit var model: AppModel
        ui { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        rule.setContent { Box(Modifier.width(411.dp).fillMaxHeight()) { App(model) {} } }
        ui { model.go(Screen.Capture) }

        val states = listOf(
            Triple(ScanStep.WAITING, 0 to 0, 2_000L), Triple(ScanStep.CONNECTED, 2 to 3, 6_000L),
            Triple(ScanStep.LOGIN, 4 to 40, 12_000L), Triple(ScanStep.LOBBY, 7 to 363, 20_000L), Triple(ScanStep.LATE, 3 to 5, 45_000L))
        for (dark in listOf(false, true)) {
            val t = if (dark) "dark" else "light"
            ui { Themes.set(ctx, dark); model.graph.scan.capture.vpnRunning = false; model.graph.scan.phase = ScanPhase.IDLE; model.graph.scan.error = null }
            feed(model, 0, 0); waitForPoll()
            rule.onNodeWithText("Not scanning").assertExists()
            shot("$t-0-idle")
            for ((step, traffic, ago) in states) {
                feed(model, traffic.first, traffic.second)
                ui { model.graph.scan.capture.vpnRunning = true; model.graph.scan.captureStarted = System.currentTimeMillis() - ago; model.graph.scan.phase = ScanPhase.CAPTURING }
                waitForPoll()
                check(model.graph.scan.step == step) { "expected $step, got ${model.graph.scan.step}" }
                rule.onAllNodesWithText(step.title)[0].assertExists()
                shot("$t-${step.ordinal + 1}-${step.name.lowercase()}")
            }
            ui { model.graph.scan.capture.vpnRunning = false; model.graph.scan.phase = ScanPhase.IDLE; model.graph.scan.error = ScanError.NOTHING }
            waitForPoll()
            shot("$t-6-nothing")
        }
        ui { model.graph.scan.capture.vpnRunning = false; model.graph.scan.phase = ScanPhase.IDLE; model.graph.scan.error = null }
        model.graph.scan.capture.clear()
    }

    /** The pill over a stand-in for the game screen, one picture per step. */
    @Test fun pillOverTheGame() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        File(dir, "pill").apply { deleteRecursively(); mkdirs() }
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() } }
        Thread.sleep(500)
        rule.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF1B2A4A))) }
        val tap = PendingIntent.getActivity(ctx, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        lateinit var pill: ScanPill
        ui { (ctx.applicationContext as E7GearApp).graph.overlay.appInFront = false; pill = ScanPill(ctx, tap) }
        for (step in ScanStep.entries) {
            ui { pill.show(step) }
            Thread.sleep(600)
            val screen = inst.uiAutomation.takeScreenshot()
            File(dir, "pill/${step.ordinal + 1}-${step.name.lowercase()}.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        ui { pill.hide() }
    }
}
