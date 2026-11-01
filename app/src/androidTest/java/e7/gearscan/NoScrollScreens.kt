package e7.gearscan

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.opt.GearPotential
import e7.gearscan.overlay.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic layout fixtures. Every interaction is a tap: no scroll actions, no hidden body text, no text entry. */
class NoScrollScreens {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performTouchInput { click() }; rule.waitForIdle() }
    private fun advance(kind: String): Boolean {
        val n = rule.onNodeWithContentDescription("Next $kind page")
        if (!n.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) return false
        n.performClick(); rule.waitForIdle(); return true
    }
    private fun menu(name: String) {
        rule.onNodeWithContentDescription("More").performClick(); rule.waitForIdle()
        repeat(10) {
            if (rule.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { tap(name); return }
            assertTrue("Missing menu item $name", advance("choices"))
        }
        fail("Menu paging did not reach $name")
    }
    private fun choose(name: String) {
        repeat(10) {
            if (rule.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { tap(name); return }
            assertTrue("Missing choice $name", advance("choices"))
        }
        fail("Choice paging did not reach $name")
    }
    private fun check() {
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("More").assertIsDisplayed()
        val body = rule.onNodeWithTag("gear-tracker-body", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val text = rule.onAllNodes(hasAnyAncestor(hasTestTag("gear-tracker-body")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(text.fetchSemanticsNodes().size) { i ->
            val b = text[i].getUnclippedBoundsInRoot()
            assertTrue("Body text outside its page: $b vs $body", b.top.value >= body.top.value - 1 && b.bottom.value <= body.bottom.value + 1)
        }
    }
    private fun shot(name: String) {
        check()
        val dir = File(ctx.getExternalFilesDir(null), "no-scroll").also { it.mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun fittedPagesAtShortHeightsAndLargeText() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) e7.gearscan.ui.GameImages.base = images.toURI().toString()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val content = Content.parse(assets.open("content.json").bufferedReader().use { it.readText() })
        var t by mutableStateOf<UpgradeTracker?>(null)
        var height by mutableIntStateOf(360)
        var font by mutableFloatStateOf(1f)
        rule.setContent {
            val d = LocalDensity.current
            val c = Configuration(LocalConfiguration.current).apply { screenHeightDp = height; screenWidthDp = 353 }
            CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalConfiguration provides c) {
                Box(Modifier.width(353.dp).height(height.dp)) { key(t) { t?.let { UpgradeOverlay(it, { _, _ -> }, {}, {}) } } }
            }
        }
        for ((h, f) in listOf(360 to 1f, 240 to 1.3f, 220 to 1.5f)) {
            val file = File(ctx.filesDir, "no-scroll-$h.json").also { it.delete() }
            val pieces = (1L..43L).map { id -> Item(id, Slot.HELMET, "SpeedSet", "Heroic", 85, 9, GearStat.HP, 2700.0,
                listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false)), false, null, main15 = 2700.0) }
            val goal = GearPotential.Goal(mapOf(GearStat.SPD to 20.0, GearStat.HP_P to 5.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
            lateinit var tracker: UpgradeTracker
            var returned = 0
            ui {
                tracker = UpgradeTracker(file)
                tracker.startQueue(pieces.map { it to UpgradeTracker.QueueEntry(it.id, goal, "Speed", content.set("SpeedSet")?.icon) }, 1)
                tracker.setName = "Speed"; tracker.setIcon = content.set("SpeedSet")?.icon
                tracker.selectQueue = { index -> tracker.select(pieces[index], 1, keepQueue = true) }
                tracker.returnToApp = { returned++ }
                OverlayState.expanded = true; OverlayState.usableHeightDp = 0f
                height = h; font = f; t = tracker
            }
            val label = "$h-${f}"
            shot("$label-piece")
            menu("Targets"); shot("$label-targets"); while (advance("details")) check(); tap("Back")
            menu("Help"); shot("$label-help"); var pages = 1; while (advance("details")) { check(); pages++; assertTrue(pages < 100) }; tap("Back")
            menu("History"); shot("$label-history"); tap("Back")
            tap("List"); shot("$label-list")
            var listPages = 1
            while (advance("list")) { check(); listPages++; assertTrue(listPages <= 43) }
            assertTrue(listPages > 1)
            tap("Speed Helmet"); assertEquals(43L, tracker.selected)
            tap("Record +12"); shot("$label-choose")
            choose("Eff"); shot("$label-total")
            rule.onNodeWithContentDescription("Increase value").performClick()
            ui { OverlayState.expanded = false }
            ui { OverlayState.expanded = true }
            rule.onNodeWithText("5").assertIsDisplayed()
            rule.onNodeWithContentDescription("Increase value").performClick()
            tap("Apply total"); shot("$label-review")
            tap("Save +12")
            assertEquals(12, tracker.record!!.item.enhance); assertEquals(6.0, tracker.record!!.item.subs.last().value, 0.0)
            menu("Correct"); choose("Spd: 14")
            rule.onNodeWithContentDescription("Increase value").performClick()
            ui { OverlayState.expanded = false }
            ui { OverlayState.expanded = true }
            rule.onNodeWithText("15").assertIsDisplayed()
            tap("Apply total"); tap("Save +12")
            assertEquals(15.0, tracker.record!!.item.subs[0].value, 0.0)
            menu("History"); tap("Undo last entry")
            assertEquals(14.0, tracker.record!!.item.subs[0].value, 0.0)
            shot("$label-saved")
            menu("Open E7 Gear"); assertEquals(1, returned)
            ui { tracker.error = "The entry could not be saved. Choose the totals shown in the game and try again. ".repeat(8) }
            shot("$label-error"); while (advance("details")) check(); tap("Back")
            assertNull(tracker.error)
        }
        // Existing-line roll: changing the chosen stat before Save must not leave two lines changed.
        val epic = Item(100, Slot.HELMET, "SpeedSet", "Epic", 85, 9, GearStat.HP, 2700.0,
            listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false), Substat(GearStat.CR, 4.0, 1, false)), false, null, main15 = 2700.0)
        lateinit var normal: UpgradeTracker
        ui {
            normal = UpgradeTracker(File(ctx.filesDir, "no-scroll-epic.json").also { it.delete() })
            normal.select(epic, 1); normal.returnToApp = {}; normal.setName = "Speed"
            height = 240; font = 1.3f; t = normal
        }
        tap("Record +12"); choose("Spd: 14"); tap("Apply total")
        tap("Back"); choose("Hp%: 5"); tap("Apply total")
        shot("240-1.3-epic-review")
        tap("Save +12")
        assertEquals(14.0, normal.record!!.item.subs[0].value, 0.0)
        assertEquals(9.0, normal.record!!.item.subs[1].value, 0.0)
        ui { t = null; OverlayState.expanded = false; OverlayState.usableHeightDp = 0f }
    }
}
