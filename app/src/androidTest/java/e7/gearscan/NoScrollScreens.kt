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
import e7.gearscan.state.Disk
import e7.gearscan.state.NoPlatform
import e7.gearscan.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import e7.gearscan.features.tracker.TrackerFeature
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
    /** A graph of its own over [items], so the panel's queue and history don't touch the app's data. */
    private fun standalone(name: String, items: List<Item>, contentText: String, onReturn: () -> Unit = {}): AppGraph {
        val dir = File(ctx.filesDir, name).also { it.deleteRecursively(); it.mkdirs() }
        File(dir, "content.json").writeText(contentText)
        val g = AppGraph(Disk(dir), CoroutineScope(Job()), platform = object : Platform by NoPlatform { override fun showApp() { onReturn() } })
        g.scan.replaceBox(Box(JSONObject().put("items", JSONArray(items.map { UpgradeRecord.itemJson(it) })).put("heroes", JSONArray())))
        g.scan.lastScan = 1
        return g
    }
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performTouchInput { click() }; rule.waitForIdle() }
    private fun advance(kind: String): Boolean {
        val n = rule.onNodeWithContentDescription("Next $kind page")
        if (!n.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) return false
        n.performClick(); rule.waitForIdle(); return true
    }
    private fun choose(name: String) {
        repeat(10) {
            val found = rule.onAllNodes(hasContentDescription(name) or hasText(name), useUnmergedTree = true)
            if (found.fetchSemanticsNodes().isNotEmpty()) { found.onFirst().performTouchInput { click() }; rule.waitForIdle(); return }
            assertTrue("Missing choice $name", advance("choices"))
        }
        fail("Choice paging did not reach $name")
    }
    private fun check() {
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Back to E7 Gear").assertIsDisplayed()
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
        val imageBase = if (images.isDirectory) images.toURI().toString() else Content.DEFAULT_IMAGE_BASE
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val contentText = assets.open("content.json").bufferedReader().use { it.readText() }
        val content = Content.parse(contentText)
        var t by mutableStateOf<AppGraph?>(null)
        var height by mutableIntStateOf(360)
        var font by mutableFloatStateOf(1f)
        rule.setContent {
            val d = LocalDensity.current
            val c = Configuration(LocalConfiguration.current).apply { screenHeightDp = height; screenWidthDp = 353 }
            CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalConfiguration provides c, e7.gearscan.ui.LocalImageBase provides imageBase) {
                Box(Modifier.width(353.dp).height(height.dp)) { key(t) { t?.let { UpgradeOverlay(it, { _, _ -> }, {}, {}) } } }
            }
        }
        for ((h, f) in listOf(360 to 1f, 240 to 1.3f, 220 to 1.5f)) {
            val pieces = (1L..43L).map { id -> Item(id, Slot.HELMET, "SpeedSet", "Heroic", 85, 9, GearStat.HP, 2700.0,
                listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false)), false, null, main15 = 2700.0) }
            val goal = GearPotential.Goal(mapOf(GearStat.SPD to 20.0, GearStat.HP_P to 5.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
            var returned = 0
            val graph = standalone("no-scroll-$h", pieces, contentText) { returned++ }
            val tracker = graph.tracker
            ui {
                tracker.startQueue(pieces.map { it to TrackerFeature.QueueEntry(it.id, goal, "Speed", content.set("SpeedSet")?.icon) }, 1)
                tracker.show()
                graph.overlay.expanded = true; graph.overlay.usableHeightDp = 0f
                height = h; font = f; t = graph
            }
            val label = "$h-${f}"
            shot("$label-piece")
            rule.onNodeWithContentDescription("Verdict details", useUnmergedTree = true).performClick(); rule.waitForIdle(); shot("$label-targets"); while (advance("details")) check(); tap("Back")
            ui { tracker.selectInQueue(tracker.pendingQueue.indexOfFirst { it.id == 43L }) }; assertEquals(43L, tracker.selected)
            tap("Record +12"); shot("$label-choose")
            choose("Eff"); shot("$label-total")
            ui { graph.overlay.expanded = false }
            ui { graph.overlay.expanded = true }
            rule.onNodeWithContentDescription("Value slider").assertIsDisplayed()
            rule.pickValue(6.0); shot("$label-review")
            tap("Save +12")
            assertEquals(12, tracker.record!!.item.enhance); assertEquals(6.0, tracker.record!!.item.subs.last().value, 0.0)
            // Correct starts from the app's Track upgrades, which opens this entry over the game
            ui { tracker.beginEdit(correction = true) }; choose("Spd: 14")
            ui { graph.overlay.expanded = false }
            ui { graph.overlay.expanded = true }
            rule.onNodeWithContentDescription("Value slider").assertIsDisplayed()
            rule.pickValue(15.0); tap("Save +12")
            assertEquals(15.0, tracker.record!!.item.subs[0].value, 0.0)
            ui { tracker.undo() }
            assertEquals(14.0, tracker.record!!.item.subs[0].value, 0.0)
            shot("$label-saved")
            rule.onNodeWithContentDescription("Back to E7 Gear").performClick(); rule.waitForIdle(); assertEquals(1, returned)
            ui { tracker.error = "The entry could not be saved. Choose the totals shown in the game and try again. ".repeat(8) }
            shot("$label-error"); while (advance("details")) check(); tap("Back")
            assertNull(tracker.error)
        }
        // Existing-line roll: changing the chosen stat before Save must not leave two lines changed.
        val epic = Item(100, Slot.HELMET, "SpeedSet", "Epic", 85, 9, GearStat.HP, 2700.0,
            listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false), Substat(GearStat.CR, 4.0, 1, false)), false, null, main15 = 2700.0)
        val epicGraph = standalone("no-scroll-epic", listOf(epic), contentText)
        val normal = epicGraph.tracker
        ui {
            normal.select(epic, 1); normal.show(); epicGraph.overlay.expanded = true
            height = 240; font = 1.3f; t = epicGraph
        }
        tap("Record +12"); choose("Spd: 14"); rule.pickValue()
        tap("Back"); tap("Back"); choose("Hp%: 5"); rule.pickValue()
        shot("240-1.3-epic-review")
        tap("Save +12")
        assertEquals(14.0, normal.record!!.item.subs[0].value, 0.0)
        assertEquals(9.0, normal.record!!.item.subs[1].value, 0.0)
        ui { t = null; epicGraph.overlay.expanded = false; epicGraph.overlay.usableHeightDp = 0f }
    }
}
