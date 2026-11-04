package e7.gearscan

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.features.sellreview.trackMatches
import e7.gearscan.overlay.UpgradeOverlay
import e7.gearscan.ui.Outcome
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The rail over the game at the owner's phone size: 792 x 353 dp landscape, the rail under the game's header. */
class RailFitScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(name: String) {
        rule.onAllNodes(hasContentDescription(name) or hasText(name), useUnmergedTree = true).onFirst().performTouchInput { click() }; rule.waitForIdle()
    }
    private fun shot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "rail-fit").also { it.mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { b -> File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun sellRailFitsTheOwnersLandscapeHeight() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }; rule.waitUntil(60_000) { graph.contentSettled() }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) graph.content.imageBase = images.toURI().toString()
        ui { graph.messages.show(graph.sellReview.find()) }; rule.waitUntil(90_000) { !graph.sellReview.busy }
        var started: Outcome? = null
        ui { started = graph.trackMatches(openOverlay = false); graph.tracker.show(); graph.overlay.expanded = true; graph.overlay.usableHeightDp = 353f }
        assertEquals(Outcome.Done, started)
        var font by mutableFloatStateOf(1f)
        var screenH by mutableIntStateOf(353)
        rule.setContent {
            val d = LocalDensity.current
            val c = Configuration(LocalConfiguration.current).apply { screenWidthDp = 792; screenHeightDp = screenH }
            CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalConfiguration provides c) {
                Box(Modifier.width(792.dp).height(screenH.dp)) { UpgradeOverlay(graph, { _, _ -> }, {}, {}) }
            }
        }
        val t = graph.tracker
        // under the game's header (RAIL_TOP 38 dp) the rail has 315 dp; the piece page must show as the inventory cell, not text pages
        fun fits(label: String) {
            val h = rule.onNodeWithTag("gear-tracker-panel").getBoundsInRoot().let { it.bottom - it.top }
            File(ctx.getExternalFilesDir(null), "rail-fit/sizes.txt").appendText("$label: rail $h, body ${rule.onNodeWithTag("gear-tracker-body").getBoundsInRoot().let { it.bottom - it.top }}\n")
            assertTrue("$label: rail $h tall, over 315 dp", h <= 315.dp)
            rule.onNodeWithContentDescription("Next details page").assertDoesNotExist()
        }
        rule.onNodeWithContentDescription("Next gear").assertDoesNotExist()
        ui { screenH = 900; graph.overlay.usableHeightDp = 900f }
        File(ctx.getExternalFilesDir(null), "rail-fit").mkdirs()
        File(ctx.getExternalFilesDir(null), "rail-fit/sizes.txt").appendText("natural body ${rule.onNodeWithTag("gear-tracker-body").getBoundsInRoot().let { it.bottom - it.top }}\n")
        ui { screenH = 353; graph.overlay.usableHeightDp = 353f }
        shot("01-sell-piece"); fits("piece")
        val soldTop = rule.onNodeWithContentDescription("Sold and next").getBoundsInRoot().top
        val verdictBottom = rule.onNodeWithContentDescription("Verdict details", useUnmergedTree = true).getBoundsInRoot().bottom
        // the tile carries the game cell's marks: its +N chip (it vanished once when the tile shrank)
        assertTrue(rule.onAllNodesWithText("+${t.record!!.item.enhance}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val worn = t.queue.indexOfFirst { e -> graph.scan.box!!.itemsById[e.id]?.equippedOn != null }
        if (worn >= 0) { ui { t.selectInQueue(worn) }; shot("01b-worn-piece"); ui { t.selectInQueue(0) } }
        val heroic = t.queue.indexOfFirst { e -> graph.records.records[e.id]!!.item.let { it.rank == "Heroic" && it.enhance == 9 && it.subs.size == 3 } }
        assertTrue(heroic >= 0)
        ui { t.selectInQueue(heroic) }; shot("02-heroic-piece"); fits("heroic piece")
        // Sold and Keep move the walk on, so they stay put from a four-substat piece to a three-substat one
        assertEquals(soldTop, rule.onNodeWithContentDescription("Sold and next").getBoundsInRoot().top)
        assertEquals(verdictBottom, rule.onNodeWithContentDescription("Verdict details", useUnmergedTree = true).getBoundsInRoot().bottom)
        fun bottom(label: String) = rule.onNodeWithContentDescription(label).getBoundsInRoot().bottom
        val lastButton = bottom("Record +12")
        tap("Record +12"); shot("03-new-substat")
        // entering a step keeps the rail's height: Cancel and Back sit where Record was
        assertEquals(lastButton, bottom("Cancel"))
        val item = t.record!!.item
        val allowed = UpgradeRecord.allowedNew(item)
        rule.onNodeWithContentDescription("Next choices page").assertDoesNotExist()
        tap(allowed.first { it != GearStat.HP }.short); shot("04-value-slider"); assertEquals(lastButton, bottom("Back"))
        rule.pickValue(); shot("05-check"); assertEquals(lastButton, bottom("Back"))
        tap("Back"); shot("06-back-to-slider")
        tap("Back")
        if (GearStat.HP in allowed) { tap(GearStat.HP.short); shot("07-flat-hp-slider"); tap("Back") }
        tap("Cancel")
        val epic = t.queue.indexOfFirst { e -> graph.records.records[e.id]!!.item.let { it.rank == "Epic" && it.enhance < 12 } }
        if (epic >= 0) { ui { t.selectInQueue(epic) }; tap("Record +${(t.record!!.item.enhance / 3 + 1) * 3}"); shot("08-which-stat"); tap("Cancel") }
        ui { font = 1.3f }; shot("09-sell-piece-font-1.3"); fits("piece at font 1.3")
        // a Find gear walk with three targets keeps the gear cell too
        val piece = graph.scan.box!!.items.first { it.rank == "Epic" && it.level == 85 && it.enhance in 3..9 && it.subs.size == 4 }
        val goal = e7.gearscan.opt.GearPotential.Goal(piece.subs.take(3).associate { it.type to it.value + 8 })
        ui {
            font = 1f
            t.startQueue(listOf(piece to e7.gearscan.features.tracker.TrackerFeature.QueueEntry(piece.id, goal, "Set", null)), graph.scan.lastScan)
            t.show(); graph.overlay.expanded = true
        }
        // one piece: nowhere to go, so no ‹ › row
        rule.onNodeWithContentDescription("Next gear").assertDoesNotExist()
        shot("10-goal-walk-3-targets"); fits("goal walk, three targets")
        val record = "Record +${(piece.enhance / 3 + 1) * 3}"
        val goalLast = bottom(record)
        tap(record); shot("11-goal-which-stat"); assertEquals(goalLast, bottom("Cancel"))
        tap("${piece.subs.first().type.short}: ${fmtNum(piece.subs.first().value)}"); shot("12-goal-slider"); assertEquals(goalLast, bottom("Back"))
        rule.pickValue(); shot("13-goal-check"); assertEquals(goalLast, bottom("Back"))
        tap("Back"); tap("Back"); tap("Cancel")
        File(ctx.getExternalFilesDir(null), "rail-fit/sizes.txt").writeText(
            "rail at 1.3: ${rule.onNodeWithTag("gear-tracker-panel").getBoundsInRoot().let { it.bottom - it.top }}; pager shown: " +
                "${rule.onAllNodesWithContentDescription("Next details page").fetchSemanticsNodes().isNotEmpty()}\n")
    }
}
