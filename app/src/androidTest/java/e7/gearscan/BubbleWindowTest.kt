package e7.gearscan

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.overlay.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bubble as a real window over another app, moved by injected touches: let go it springs to the nearer side, a fling
 * picks the side, the dismiss target holds it and closes the overlay, and in landscape a tap opens the panel.
 * Screenshots under bubble/ in the app's external files dir. Emulator only.
 */
class BubbleWindowTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val d get() = ctx.resources.displayMetrics.density
    private val dir get() = File(ctx.getExternalFilesDir(null), "bubble").also { it.mkdirs() }
    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }
    private fun main(f: () -> Unit) = inst.runOnMainSync(f)
    private fun shot(name: String) = inst.uiAutomation.takeScreenshot().also { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun inject(down: Long, action: Int, p: PointF, at: Long = SystemClock.uptimeMillis()) {
        val e = MotionEvent.obtain(down, at, action, p.x, p.y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(inst.uiAutomation.injectInputEvent(e, true)); e.recycle()
    }

    /**
     * A finger from [a] to [b] over [ms]; [hold] ms still before letting go (a slow drop), [before] runs just before it lets
     * go. Event times follow the intended pace (a real lift comes within a frame of the last move); injecting is slower.
     */
    private fun drag(a: PointF, b: PointF, ms: Long, hold: Long = 0, before: () -> Unit = {}) {
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, a, down)
        val steps = (ms / 8).coerceAtLeast(4).toInt()
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            inject(down, MotionEvent.ACTION_MOVE, PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), down + ms * i / steps)
        }
        var at = down + ms
        repeat((hold / 16).toInt()) { at += 16; inject(down, MotionEvent.ACTION_MOVE, b, at); Thread.sleep(16) }
        before()
        inject(down, MotionEvent.ACTION_UP, b, at + 8)
    }

    private var lastFinger: PointF? = null

    private fun bubble() = checkNotNull(OverlayState.bubbleOnScreen) { "the bubble isn't on screen" }
    private fun center() = bubble().let { PointF(it.exactCenterX(), it.exactCenterY()) }
    /** The usable area (between the system bars) in display coordinates. */
    private fun area() = ctx.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.let { m ->
        val i = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        android.graphics.Rect(i.left, i.top, m.bounds.width() - i.right, m.bounds.height() - i.bottom)
    }
    private fun settle() = Thread.sleep(1200)

    /** Until the tracker's bubble is drawn: a cold debug build takes ~2 s for the overlay's first frame on the emulator. */
    private fun waitForTrackerBubble() {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            OverlayState.bubbleOnScreen?.let { r ->
                val shot = inst.uiAutomation.takeScreenshot()
                // the slate face left of the gear glyph (the badge covers the top)
                val px = shot.getPixel((r.centerX() - 18 * d).toInt(), r.centerY())
                shot.recycle()
                if (android.graphics.Color.luminance(px) < 0.3f) { Thread.sleep(400); return }
            }
            Thread.sleep(200)
        }
        fail("the tracker bubble never showed: ${OverlayState.bubbleOnScreen}")
    }
    private fun onRight() = bubble().right == area().right
    private fun onLeft() = bubble().left == area().left

    @Test fun dragSnapFlingDismiss() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        fun gear(id: Long, slot: Slot) = Item(id, slot, "SpeedSet", "Epic", 85, 9, GearStat.CR, 40.0,
            listOf(Substat(GearStat.SPD, 7.0, 2, false), Substat(GearStat.CD, 8.0, 2, false), Substat(GearStat.HP_P, 12.0, 2, false)), false, null, main15 = 60.0)
        val piece = gear(1, Slot.NECKLACE)
        val probe = File(ctx.filesDir, "bubble-window-test.json").also { it.delete() }
        lateinit var tracker: UpgradeTracker
        main { tracker = UpgradeTracker(probe); tracker.select(piece, 1) }
        shell("appops set e7.gearscan SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        dir.deleteRecursively()
        // ends a touch an earlier, failed run left down; rejected (and ignored) when there is none
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN; inst.uiAutomation.injectInputEvent(it, true); it.recycle() }
        val log = StringBuilder()
        try {
            main { OverlayState.bubbleLeft = false; OverlayState.upgrade = tracker; OverlayState.expanded = false; OverlayState.appInFront = false; OverlayService.start(ctx) }
            shell("am start -a android.intent.action.VIEW -d about:blank -p com.android.chrome")
            waitForTrackerBubble()
            assertTrue("the bubble starts on the right: ${bubble()} in ${area()}", onRight())
            shot("1-tracker-bubble")

            // a slow drop left of the middle: springs to the left edge, about where it was let go
            val a = area()
            val drop = PointF(a.left + a.width() * 0.35f, a.top + a.height() * 0.4f)
            drag(center(), drop, 600, hold = 200)
            settle()
            log.appendLine("slow drop at ${drop}: ${bubble()}")
            assertTrue("a slow drop left of the middle didn't go left: ${bubble()}", onLeft())
            assertEquals("the bubble moved up or down after a slow drop", drop.y, center().y, 40 * d)
            assertTrue("the bubble rests left but doesn't know it", OverlayState.bubbleLeft)
            shot("2-snapped-left")

            // a short quick fling right, still left of the middle: the fling picks the side
            val from = center()
            drag(from, PointF(from.x + a.width() * 0.15f, from.y), 60)
            settle()
            log.appendLine("fling right from $from: ${bubble()}")
            assertTrue("a fling right didn't go right: ${bubble()}", onRight())
            shot("3-flung-right")

            // into the target and out again without letting go: once out, the bubble is back under the moving finger
            val target = PointF(a.exactCenterX(), a.bottom - (TARGET_MARGIN + TARGET_BOX / 2f) * d)
            run {
                val start = center()
                val down = SystemClock.uptimeMillis()
                var at = down
                inject(down, MotionEvent.ACTION_DOWN, start, at)
                fun moveTo(p: PointF, ms: Long) {
                    val from = lastFinger ?: start
                    val steps = (ms / 16).toInt()
                    for (i in 1..steps) {
                        at += 16
                        val q = PointF(from.x + (p.x - from.x) * i / steps, from.y + (p.y - from.y) * i / steps)
                        inject(down, MotionEvent.ACTION_MOVE, q, at); lastFinger = q
                    }
                }
                moveTo(target, 500)
                Thread.sleep(300)
                assertTrue("the target didn't take the bubble", OverlayState.captured)
                // out and up at a steady pace; the finger is still moving when the bubble is measured
                moveTo(PointF(target.x, target.y - a.height() * 0.35f), 700)
                val finger = lastFinger!!
                val lag = Math.hypot((center().x - finger.x).toDouble(), (center().y - finger.y).toDouble()) / d
                val held = OverlayState.captured
                log.appendLine("out of the target: bubble ${center()} finger $finger, lag ${"%.1f".format(lag)} dp")
                shot("3b-out-of-target")
                // lifted before asserting: a finger left down makes the next run's touches inconsistent
                repeat(10) { at += 16; inject(down, MotionEvent.ACTION_MOVE, finger, at); Thread.sleep(16) }
                inject(down, MotionEvent.ACTION_UP, finger, at + 8)
                lastFinger = null
                assertFalse("still held after leaving the target", held)
                assertTrue("the bubble trails the finger by ${"%.0f".format(lag)} dp after leaving the target", lag < 12)
                settle()
            }

            // onto the dismiss target: it holds the bubble, letting go closes the overlay
            drag(center(), PointF(target.x + 30 * d, target.y - 40 * d), 700, hold = 400) {
                assertTrue("the target didn't take the bubble", OverlayState.captured)
                shot("4-held-by-target")
            }
            Thread.sleep(900)
            log.appendLine("dropped on the target: running=${OverlayState.running}")
            assertFalse("dropping on the target didn't close the overlay", OverlayState.running)

            // landscape: the bubble snaps there too and a tap opens the panel
            main { OverlayState.upgrade = tracker; OverlayState.expanded = false; OverlayService.start(ctx) }
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            for (i in 0 until 30) { if (area().width() > area().height()) break; Thread.sleep(200) }
            settle()
            val l = area()
            assertTrue("the screen didn't turn", l.width() > l.height())
            shot("5-landscape-bubble")
            drag(center(), PointF(l.left + l.width() * 0.62f, l.top + l.height() * 0.3f), 500, hold = 200)
            settle()
            log.appendLine("landscape drop: ${bubble()} in $l")
            assertTrue("a landscape drop right of the middle didn't go right: ${bubble()}", onRight())
            shot("6-landscape-snapped")
            val c = center()
            val down = SystemClock.uptimeMillis()
            inject(down, MotionEvent.ACTION_DOWN, c); Thread.sleep(60); inject(down, MotionEvent.ACTION_UP, c)
            Thread.sleep(800)
            assertTrue("a tap didn't open the panel", OverlayState.expanded)
            shot("7-landscape-panel")
            main { OverlayService.stop(ctx) }
            Thread.sleep(400)

            // the checklist's bubble: the hero's face, a progress ring and the next step beside it
            shell("settings put system user_rotation 0")
            val build = OverlayBuild("Build #1", null, listOf(
                OverlayPiece(piece, null, "Crit 60%", emptyList(), "in bag", false),
                OverlayPiece(gear(2, Slot.RING), null, "Atk 65%", emptyList(), "on Silk", false),
                OverlayPiece(gear(3, Slot.BOOTS), null, "Spd 45", emptyList(), "on now", true)), emptyList())
            main { OverlayState.load(build); OverlayState.appInFront = false; OverlayState.bubbleLeft = false; OverlayService.start(ctx) }
            // a new service composes its windows anew: give its first frame time on a slow emulator
            Thread.sleep(2500)
            shot("8-checklist-bubble-hint")
            drag(center(), PointF(area().left + area().width() * 0.2f, area().top + area().height() * 0.5f), 500, hold = 200)
            settle()
            assertTrue("the checklist bubble didn't go left: ${bubble()}", onLeft())
            shot("9-checklist-bubble-left")
        } finally {
            File(dir, "result.txt").writeText(log.toString())
            main { OverlayService.stop(ctx); OverlayState.upgrade = null; OverlayState.build = null; OverlayState.appInFront = true; OverlayState.bubbleLeft = false }
            shell("settings put system user_rotation 0")
            shell("settings put system accelerometer_rotation 1")
            probe.delete()
        }
    }
}
