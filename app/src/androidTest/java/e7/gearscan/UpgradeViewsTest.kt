package e7.gearscan

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
import e7.gearscan.opt.UpgradeCheck
import e7.gearscan.overlay.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Layout fixtures for the additional branches; these sample results are deliberately synthetic. */
class UpgradeViewsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { val n = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst(); runCatching { n.performScrollTo() }; n.performTouchInput { click() }; rule.waitForIdle() }
    private fun shot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "upgrade").also { it.mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun heroicRevealAndDecisionViews() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val piece = Item(1, Slot.HELMET, "SpeedSet", "Heroic", 85, 9, GearStat.HP, 2700.0,
            listOf(Substat(GearStat.SPD, 7.0, 2, false), Substat(GearStat.CR, 8.0, 2, false), Substat(GearStat.HP_P, 12.0, 2, false)), false, null, main15 = 2700.0)
        lateinit var t: UpgradeTracker
        val file = File(ctx.filesDir, "upgrade-layout-test.json").also { it.delete() }
        ui { t = UpgradeTracker(file); t.select(piece, 1); OverlayState.upgrade = t; OverlayState.expanded = true }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                Box(Modifier.width(353.dp).fillMaxHeight()) { OverlayUi({ _, _ -> }, {}, {}) }
            }
        }
        tap("Record +12")
        shot("11-heroic-reveal-353-big")
        tap("Eff")
        shot("12-touch-controls-353-big")
        repeat(2) { rule.onNodeWithContentDescription("Increase value").performClick() }
        tap("Apply total"); tap("Save +12")
        assertEquals(4, t.record!!.item.subs.size)
        ui {
            t.begin("synthetic-layout-only", 1)
            t.checkpoint(listOf(UpgradeCheck.HeroResult(1, "Hero with a long saved setup", (0 until 17).toList(), false, false, 0, false, 1.0, "Hp +200 · Spd +4 · Def -20")))
            t.busy = false
        }
        shot("13-continue-layout-fixture")
        ui { t.checkpoint(listOf(UpgradeCheck.HeroResult(1, "Hero with a long saved setup", emptyList(), true, false, 0, false, 1.0, "Hp +200"))) }
        assertEquals("Pause · useful as it is", t.title)
        shot("14-pause-layout-fixture")
        ui { OverlayState.upgrade = null }
        file.delete()
    }
}
