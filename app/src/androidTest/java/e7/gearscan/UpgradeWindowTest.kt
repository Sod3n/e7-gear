package e7.gearscan

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.overlay.*
import e7.gearscan.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real separate windows over another app, including landscape and direct touches in the editor. */
class UpgradeWindowTest {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val graph get() = (ctx.applicationContext as E7GearApp).graph
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }
    private fun main(f: () -> Unit) = inst.runOnMainSync(f)
    private fun tapAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(event, true)); event.recycle(); Thread.sleep(80)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        Thread.sleep(200)
    }
    private fun shot(name: String): Bitmap {
        val b = inst.uiAutomation.takeScreenshot()
        val dir = File(ctx.getExternalFilesDir(null), "upgrade").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    @Test fun realWindowOverBrowser() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val tracker = graph.tracker
        val piece = tracker.record?.history?.firstOrNull() ?: Item(1, Slot.HELMET, "SpeedSet", "Heroic", 85, 9, GearStat.HP, 1512.0,
            listOf(Substat(GearStat.SPD, 7.0, 2, false), Substat(GearStat.CR, 8.0, 2, false), Substat(GearStat.HP_P, 12.0, 2, false)), false, null, main15 = 2700.0)
        val scan = tracker.record?.scan ?: 1
        main { tracker.select(piece, scan) }
        shell("appops set e7.gearscan SYSTEM_ALERT_WINDOW allow")
        val info = inst.uiAutomation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        inst.uiAutomation.serviceInfo = info
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        try {
            main { graph.tracker.show(); graph.overlay.expanded = true; graph.overlay.appInFront = false; OverlayService.start(ctx) }
            Thread.sleep(700)
            // the overlay's compositions run on this rule's clock too: let the panel's opening animation finish
            rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
            shell("am start -a android.intent.action.VIEW -d about:blank -p com.android.chrome")
            Thread.sleep(700)
            shot("07-over-browser-portrait")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            // the emulator's rotation animation outlasts a fixed sleep: wait until two shots in a row match
            var landscape = shot("08-over-browser-landscape")
            for (i in 0 until 16) {
                Thread.sleep(500)
                val next = shot("08-over-browser-landscape")
                val settled = next.width > next.height && next.sameAs(landscape)
                landscape = next
                if (settled) break
            }
            assertTrue("browser did not rotate", landscape.width > landscape.height)
            // Non-focusable Compose overlay text is absent from this emulator's accessibility-window roots.
            // Locate the solid pink Record action in the screenshot, then inject a real touch.
            val density = ctx.resources.displayMetrics.density
            fun pink(px: Int, py: Int) = landscape.getPixel(px, py).let { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 160 && android.graphics.Color.blue(it) > 100 }
            // the rail starts after any display cutout, so look for the pink Record button across the left of the screen
            var x = -1
            val runs = mutableListOf<IntRange>()
            for (xDp in 8..200 step 8) {
                val cx = (xDp * density).toInt()
                var start = -1
                for (y in 0 until landscape.height) {
                    val p = pink(cx, y)
                    if (p && start < 0) start = y
                    if (!p && start >= 0) { if (y - start > 30) runs += start until y; start = -1 }
                }
                if (runs.isNotEmpty()) { x = cx; break }
            }
            assertTrue("Record control is not visible", runs.isNotEmpty())
            val record = runs.first()
            var right = x
            // along the button's upper edge, above its label, to find where the button (and so the rail) ends
            val edgeRow = record.first + (3 * density).toInt()
            while (right + 1 < landscape.width && pink(right + 1, edgeRow)) right++
            tapAt(x.toFloat(), (record.first + record.last) / 2f)
            assertTrue(tracker.editing)
            shot("09-editor-landscape")
            val topInset = if (Build.VERSION.SDK_INT >= 30) ctx.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top else 0
            // the rail's collapse button sits at the right end of its top row: find the rail's top border above it in the shot
            val cx = (right - 15 * density).toInt()
            val top = (0 until record.first).firstOrNull { y -> landscape.getPixel(cx, y).let { android.graphics.Color.red(it) < 90 && android.graphics.Color.green(it) < 90 && android.graphics.Color.blue(it) < 140 } }
            assertTrue("rail top border not found (inset $topInset)", top != null)
            tapAt(cx.toFloat(), top!! + 20 * density)
            assertFalse(graph.overlay.expanded)
            shot("10-bubble-landscape")
        } finally {
            main { OverlayService.stop(ctx); graph.tracker.hide(); graph.overlay.appInFront = true }
            shell("settings put system user_rotation 0")
            shell("settings put system accelerometer_rotation 1")
            main { tracker.restore(org.json.JSONObject()) }
        }
    }
}
