package e7.gearscan

import e7.gearscan.ui.trackMatch
import e7.gearscan.ui.trackAllMatches
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.overlay.*
import e7.gearscan.ui.App
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Offline personal fixtures, emulator only. Exercises the user's Speed 20 query and joint-target overlay flow. */
class GearPotentialScreens {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val shots get() = File(ctx.getExternalFilesDir(null), "touch").also { it.mkdirs() }
    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }
    private fun tap(text: String) { val n = rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst(); runCatching { n.performScrollTo() }; n.performTouchInput { click() }; rule.waitForIdle() }
    private fun shot(name: String) { rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0); (if (rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1) inst.uiAutomation.takeScreenshot() else rule.onRoot().captureToImage().asAndroidBitmap()).let { b -> File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }

    /**
     * Design rounds: every Find gear state and sheet at 411 dp and 353 dp x1.3 (plus dark), from real searches on E7_FIXTURES'
     * backup (each search takes well under a second), into files/findgear/. Run with
     * -Pandroid.testInstrumentationRunnerArguments.class=e7.gearscan.GearPotentialScreens#designRender
     */
    @Test fun designRender() {
        assumeTrue("replaces account state; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val out = File(ctx.getExternalFilesDir(null), "findgear").also { it.deleteRecursively(); it.mkdirs() }
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); graph.settings.consent = true
            e7.gearscan.ui.Themes.set(ctx, false); graph.findGear.edit(PotentialQuery()) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) graph.content.imageBase = images.toURI().toString()
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.testTag("frame").width(width.dp).fillMaxHeight()) { if (overlay) androidx.compose.runtime.CompositionLocalProvider(e7.gearscan.ui.LocalImageBase provides graph.content.imageBase) { OverlayUi(graph, { _, _ -> }, {}, {}) } else App(graph) {} }
            }
        }
        fun snap(name: String) {
            rule.waitForIdle(); Thread.sleep(400); rule.waitForIdle()
            val b = if (rule.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1) inst.uiAutomation.takeScreenshot()
                else rule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val setups = listOf(411 to 1f, 353 to 1.3f)
        fun each(name: String, at: () -> Unit = {}) {
            for ((w, f) in setups) { ui { width = w; font = f }; at(); snap("$name-$w-${if (f > 1f) "big" else "1x"}") }
        }
        fun go(vararg s: Screen) = ui { graph.nav.sheet = null; graph.nav.stack = s.toList() }
        fun sheet(kind: SheetKind, id: Long = 0) = ui { graph.nav.sheet = Sheet(kind, id) }
        fun search() { ui { graph.messages.toast = null; graph.messages.show(graph.findGear.find()) }; rule.waitUntil(30_000) { !graph.findGear.busy }; assertNull(graph.messages.toast) }
        fun scrollTo(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).onFirst().performScrollTo()

        each("00-gear-tab-first") { go(Screen.Gear) }
        each("01-setup") { go(Screen.Gear, Screen.GearPotential) }
        each("01b-matches-before-search") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        go(Screen.Gear, Screen.GearPotential); tap("Set up")
        each("02a-targets") { sheet(SheetKind.POTENTIAL_TARGETS) }
        each("02b-slots") { sheet(SheetKind.POTENTIAL_SLOTS) }
        each("02c-sets") { sheet(SheetKind.POTENTIAL_SETS) }
        each("02d-mains") { sheet(SheetKind.POTENTIAL_MAINS) }
        each("02e-finish") { sheet(SheetKind.POTENTIAL_FINISH) }
        each("02e-chance") { sheet(SheetKind.POTENTIAL_CHANCE) }
        each("02e-pieces") { sheet(SheetKind.POTENTIAL_PIECES) }
        each("02f-help") { sheet(SheetKind.POTENTIAL_HELP) }
        for ((w, f) in setups) {
            ui { width = w; font = f }
            sheet(SheetKind.POTENTIAL_TARGETS); rule.onAllNodesWithContentDescription("Type a value").onFirst().performClick()
            snap("02g-number-$w-${if (f > 1f) "big" else "1x"}"); ui { graph.nav.sheet = null }
        }
        ui { graph.nav.sheet = null }

        go(Screen.Gear, Screen.GearPotential)
        search()
        val r = graph.findGear.result!!
        File(out, "result.txt").writeText("${r.matches.size} matches, ${r.matches.count { it.outcome.chance > 0 }} ordinary, " +
            "${r.matches.count { it.outcome.rareOnly }} rare-only, ${r.unsupported} unsupported, ${r.seconds}s\n")
        each("03a-results") { go(Screen.Gear, Screen.GearPotential) }
        each("03b-results-low-odds") { go(Screen.Gear, Screen.GearPotential); tap("Show 20 more"); scrollTo("Show 20 more") }
        if (r.rareOnly.isNotEmpty()) {
            each("03c-results-end") { go(Screen.Gear, Screen.GearPotential); scrollTo("need a rare roll") }
            each("03e-rare-open") { go(Screen.Gear, Screen.GearPotential); rule.onAllNodesWithText("need a rare roll", substring = true, useUnmergedTree = true).onFirst().performScrollTo().performClick(); rule.waitForIdle()
                rule.onAllNodesWithText("Rare", useUnmergedTree = true).onFirst().performScrollTo() }
        }
        ui { graph.findGear.edit(PotentialQuery(targets = mapOf(GearStat.SPD to "24"))) }
        search()
        File(out, "result.txt").appendText("speed 24: ${graph.findGear.result!!.matches.size} matches, ${graph.findGear.result!!.matches.count { it.outcome.rareOnly }} rare-only\n")
        each("03d-results-speed24") { go(Screen.Gear, Screen.GearPotential) }
        ui { graph.findGear.edit(PotentialQuery()) }
        search()
        val best = r.matches.first()
        val rare = r.matches.firstOrNull { it.outcome.rareOnly }
        val reforge = r.matches.firstOrNull { it.item.enhance == 15 && !it.outcome.matchesNow }
        each("04a-piece-best") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, best.item.id) }
        if (rare != null) each("04b-piece-rare") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, rare.item.id) }
        val usual = r.matches.first { it.item.enhance < 15 && it.outcome.chance > 0 && it.outcome.chance < 1 }
        each("04d-piece-usual") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, usual.item.id) }
        if (reforge != null) each("04c-piece-reforge-only") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, reforge.item.id) }
        each("05-gear-tab-after") { go(Screen.Gear) }
        // a filter changed after the search: the list is out of date
        ui { graph.findGear.edit(graph.findGear.query.copy(slots = setOf(Slot.HELMET, Slot.NECKLACE))) }
        each("06a-stale") { go(Screen.Gear, Screen.GearPotential) }
        ui { graph.messages.show(graph.trackAllMatches(openOverlay = false)) }
        File(out, "result.txt").appendText("track all on a stale list: screen=${graph.nav.screen} toast=${graph.messages.toast?.text}\n")
        each("06b-stale-after-track-all") { go(Screen.Gear, Screen.GearPotential) }
        // joint targets on one set and slot
        ui { graph.findGear.edit(PotentialQuery(targets = mapOf(GearStat.SPD to "20", GearStat.HP_P to "5"), sets = setOf("DestructionSet"), slots = setOf(Slot.HELMET))) }
        search()
        each("07a-joint-setup") { go(Screen.Gear, Screen.GearPotential); tap("Set up") }
        each("07b-joint-results") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        each("07c-joint-targets-sheet") { go(Screen.Gear, Screen.GearPotential); tap("Set up"); sheet(SheetKind.POTENTIAL_TARGETS) }
        ui { graph.findGear.edit(PotentialQuery(minimumChance = "30")) }
        search()
        each("08a-min-chance-30") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        ui { graph.findGear.edit(PotentialQuery(minimumChance = "30", show = 1)) }
        search()
        check(graph.findGear.result!!.matches.isEmpty()) { "the empty-state query found ${graph.findGear.result!!.matches.size}" }
        each("08b-empty") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        ui { graph.findGear.edit(PotentialQuery(show = 2)) }
        search()
        File(out, "result.txt").appendText("all gear: ${graph.findGear.result!!.matches.size} matches, ${graph.findGear.result!!.matches.count { it.outcome.matchesNow }} ready now\n")
        each("09a-all-gear") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        graph.findGear.result!!.matches.firstOrNull { it.outcome.matchesNow }?.let { m ->
            each("09b-piece-ready-now") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, m.item.id) }
        }
        // what tracking hands over to: all matches first (fresh list), then one unfinished piece, then back to the list
        ui { graph.findGear.edit(PotentialQuery()) }
        search()
        val first = graph.findGear.result!!.matches.first { it.item.enhance < 15 && it.outcome.chance > 0 }
        go(Screen.Gear, Screen.GearPotential)
        ui { graph.messages.show(graph.trackAllMatches(openOverlay = false)) }
        File(out, "result.txt").appendText("track all on a fresh list: screen=${graph.nav.screen} queue=${graph.tracker.queue.size} toast=${graph.messages.toast?.text}\n")
        each("10a-track-all") { ui { graph.nav.sheet = null } }
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        each("10b-overlay-all")
        ui { overlay = false; graph.tracker.hide() }
        ui { graph.findGear.edit(PotentialQuery()) }
        search()
        ui { graph.messages.show(graph.trackMatch(graph.findGear.result!!.matches.first { it.item.id == first.item.id })) }
        File(out, "result.txt").appendText("track one: stale=${graph.findGear.stale} (a start-only record must not mark the list stale)\n")
        File(out, "result.txt").appendText("back link after tracking one from Track upgrades: ${graph.nav.backLabel()}\n")
        each("10c-track-one") { ui { graph.nav.sheet = null } }
        each("10f-track-choose") { go(Screen.Gear, Screen.GearPotential, Screen.Upgrade); tap("Other piece") }
        ui { graph.nav.stack = graph.nav.stack.dropLast(1) + Screen.Upgrade }
        ui {
            val rec = graph.tracker.record!!.item
            val next = rec.enhance / 3 * 3 + 3
            val reveal = if (rec.subs.size < 4 && next == 12) UpgradeRecord.allowedNew(rec).first().let { it to e7.gearscan.opt.Finish.range(it, rec.level).first.toDouble() } else null
            if (rec.enhance < 15) File(out, "result.txt").appendText("recorded +$next: " + graph.tracker.commit(UpgradeRecord.mainAt(rec, next),
                rec.subs.mapIndexed { i, x -> x.value + if (i == 0 && reveal == null) e7.gearscan.opt.Finish.range(x.type, rec.level).first else 0 }, reveal, false) + "\n")
        }
        each("10g-track-rolls") { ui { graph.nav.sheet = null } }
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        each("10d-overlay-one")
        ui { overlay = false; graph.tracker.hide() }
        each("10e-back-after-tracking-one") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        // dark theme, the main states
        ui { e7.gearscan.ui.Themes.set(ctx, true); width = 411; font = 1f }
        go(Screen.Gear, Screen.GearPotential); tap("Set up"); snap("11a-dark-setup")
        ui { graph.findGear.edit(PotentialQuery()) }
        search()
        go(Screen.Gear, Screen.GearPotential); tap("Matches"); snap("11b-dark-results")
        sheet(SheetKind.POTENTIAL_PIECE, first.item.id); snap("11c-dark-piece")
        ui { graph.nav.sheet = null; e7.gearscan.ui.Themes.set(ctx, false) }
    }

    @Test fun searchFilterAndTrackTargets() {
        assumeTrue("replaces account state; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var graph: AppGraph
        ui { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); graph.nav.go(Screen.GearPotential) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) graph.content.imageBase = images.toURI().toString()
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) androidx.compose.runtime.CompositionLocalProvider(e7.gearscan.ui.LocalImageBase provides graph.content.imageBase) { OverlayUi(graph, { _, _ -> }, {}, {}) } else App(graph) {} }
            }
        }
        shot("01-filters-411")
        ui { width = 353; font = 1.3f }
        shot("01-filters-353-big")
        ui { graph.nav.sheet = Sheet(SheetKind.POTENTIAL_SLOTS, 0) }
        shot("01-slots-353-big"); tap("Done")
        ui { graph.nav.sheet = Sheet(SheetKind.POTENTIAL_MAINS, 0) }
        shot("01-mains-353-big"); tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !graph.findGear.busy }
        assertNull(graph.messages.toast)
        val first = graph.findGear.result!!
        assertTrue(first.matches.isNotEmpty())
        assertTrue(first.matches.none { it.item.slot == Slot.BOOTS })
        assertTrue(first.matches.any { it.item.enhance == 15 && it.outcome.chance == 1.0 && !it.outcome.matchesNow })
        assertTrue(first.matches.any { it.outcome.rareOnly })
        File(shots, "initial-results.txt").writeText("${first.matches.size} matches, ${first.matches.count { it.outcome.chance > 0 }} ordinary, ${first.matches.count { it.outcome.rareOnly }} rare-only, ${first.unsupported} unsupported, ${first.seconds}s\n" +
            first.matches.joinToString("\n") { "${it.item.id} ${it.item.slot} ${it.item.set} +${it.item.enhance}: ${it.outcome.label()}" })
        shot("02-results-353-big")
        tap("Edit")
        tap("Sets")
        tap("Destruction")
        assertEquals(setOf("DestructionSet"), graph.findGear.query.sets)
        shot("03-sets-353-big")
        tap("Done")
        ui { graph.findGear.edit(graph.findGear.query.copy(slots = setOf(Slot.HELMET))) }
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !graph.findGear.busy }
        assertTrue(graph.findGear.result!!.matches.all { it.item.slot == Slot.HELMET && it.item.set == "DestructionSet" })
        assertTrue(graph.findGear.result!!.matches.any { kotlin.math.abs(it.outcome.chance - .25) < 1e-10 })
        shot("04-filtered-353-big")
        tap("Edit"); tap("Speed 20+"); tap("Hp%")
        assertTrue(GearStat.HP_P in graph.findGear.query.targets)
        rule.onAllNodesWithContentDescription("Type a value").onLast().performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("5")
        shot("05-number-controls-353-big")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertEquals("5", graph.findGear.query.targets[GearStat.HP_P])
        shot("05-joint-targets-353-big")
        tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !graph.findGear.busy }
        val best = graph.findGear.result!!.matches.first()
        assertEquals(.25, best.outcome.chance, 1e-10)
        shot("06-joint-results-353-big")
        // Empty-state feedback and minimum-chance filter.
        tap("Edit")
        tap("Chance")
        ui { graph.findGear.edit(e7.gearscan.ui.withChanceStop(graph.findGear.query, e7.gearscan.ui.CHANCE_STOPS.indexOf(30.0))) }
        assertEquals("30", graph.findGear.query.minimumChance)
        shot("06-options-353-big"); tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !graph.findGear.busy }
        assertTrue(graph.findGear.result!!.matches.isEmpty())
        assertEquals(.25, graph.findGear.result!!.bestBelow!!.outcome.chance, 1e-10)
        shot("07-empty-353-big")
        tap("Show 20% and up")
        rule.waitUntil(30_000) { !graph.findGear.busy }
        assertEquals("20", graph.findGear.query.minimumChance)
        assertTrue(graph.findGear.result!!.withOdds.isNotEmpty())
        ui { graph.findGear.edit(graph.findGear.query.copy(minimumChance = "0")); graph.messages.show(graph.findGear.find()) }
        rule.waitUntil(30_000) { !graph.findGear.busy }
        tap("${graph.content.content.set(best.item.set)?.name} ${best.item.slot.label}")
        assertEquals(SheetKind.POTENTIAL_PIECE, graph.nav.sheet!!.kind)
        shot("07-piece-353-big")

        ui { graph.messages.show(graph.trackMatch(best)) }
        assertEquals(Screen.Upgrade, graph.nav.screen)
        assertEquals(mapOf(GearStat.SPD to 20.0, GearStat.HP_P to 5.0), graph.tracker.goal!!.minimums)
        shot("08-track-selected-353-big")
        tap("Other piece")
        shot("08-picker-353-big")
        tap("TRACKING")
        assertEquals(best.item.id, graph.tracker.record!!.item.id)
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        assertEquals(.25, graph.tracker.readout.goalOutcome!!.chance, 1e-10)
        shot("09-target-overlay-353-big")
        tap("Record +12")
        shot("09-editor-353-big")
        tap("Cancel")
        ui { graph.overlay.expanded = false }
        shot("09-bubble-353-big")
        ui { graph.overlay.expanded = true }
        // The Heroic +12 reveal leaves its final 25% Speed hit; a later miss makes Speed 20 impossible.
        ui {
            val r = graph.tracker.record!!.item
            assertEquals("Heroic", r.rank); assertEquals(9, r.enhance)
            assertTrue(graph.tracker.commit(UpgradeRecord.mainAt(r, 12), r.subs.map { it.value }, GearStat.EFF to 6.0, false))
        }
        assertEquals(.25, graph.tracker.readout.goalOutcome!!.chance, 1e-10)
        ui {
            val r = graph.tracker.record!!.item
            assertTrue(graph.tracker.commit(UpgradeRecord.mainAt(r, 15), r.subs.map { it.value + if (it.type == GearStat.HP_P) 4 else 0 }, null, false))
        }
        assertFalse(graph.tracker.readout.goalOutcome!!.possible)
        assertEquals("Targets no longer reachable", graph.tracker.readout.title)
        val restored = reopenTracker(graph, ctx.filesDir)
        assertEquals(graph.tracker.goal, restored.goal)
        assertEquals(15, restored.record!!.item.enhance)
        shot("10-missed-target-353-big")
        val backup = JSONObject(graph.scan.backupText())
        assertEquals(graph.findGear.query, PotentialQuery.of(backup.getJSONObject("fields").getJSONObject("findGear.query")))
        ui { graph.tracker.hide() }
    }
}
