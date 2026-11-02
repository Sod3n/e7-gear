package e7.gearscan

import android.app.Application
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
        lateinit var model: AppModel
        ui { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { assertTrue(model.graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); model.consent = true
            e7.gearscan.ui.Themes.set(ctx, false); model.graph.findGear.edit(PotentialQuery()) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) e7.gearscan.ui.GameImages.base = images.toURI().toString()
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.testTag("frame").width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
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
        fun go(vararg s: Screen) = ui { model.sheet = null; model.stack = s.toList() }
        fun sheet(kind: SheetKind, id: Long = 0) = ui { model.sheet = Sheet(kind, id) }
        fun search() { ui { model.toast = null; model.graph.messages.show(model.graph.findGear.find()) }; rule.waitUntil(30_000) { !model.graph.findGear.busy }; assertNull(model.toast) }
        fun scrollTo(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).onFirst().performScrollTo()

        each("00-gear-tab-first") { go(Screen.Gear) }
        each("01-setup") { go(Screen.Gear, Screen.GearPotential) }
        each("01b-matches-before-search") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        go(Screen.Gear, Screen.GearPotential); tap("Set up")
        each("02a-targets") { sheet(SheetKind.POTENTIAL_TARGETS) }
        each("02b-slots") { sheet(SheetKind.POTENTIAL_SLOTS) }
        each("02c-sets") { sheet(SheetKind.POTENTIAL_SETS) }
        each("02d-mains") { sheet(SheetKind.POTENTIAL_MAINS) }
        each("02e-options") { sheet(SheetKind.POTENTIAL_OPTIONS) }
        each("02f-help") { sheet(SheetKind.POTENTIAL_HELP) }
        for ((w, f) in setups) {
            ui { width = w; font = f }
            sheet(SheetKind.POTENTIAL_TARGETS); rule.onNodeWithContentDescription("Minimum Spd").performClick()
            snap("02g-number-$w-${if (f > 1f) "big" else "1x"}"); tap("Cancel")
        }
        ui { model.sheet = null }

        go(Screen.Gear, Screen.GearPotential)
        search()
        val r = model.graph.findGear.result!!
        File(out, "result.txt").writeText("${r.matches.size} matches, ${r.matches.count { it.outcome.chance > 0 }} ordinary, " +
            "${r.matches.count { it.outcome.rareOnly }} rare-only, ${r.unsupported} unsupported, ${r.seconds}s\n")
        each("03a-results") { go(Screen.Gear, Screen.GearPotential) }
        each("03b-results-low-odds") { go(Screen.Gear, Screen.GearPotential); tap("Show 20 more"); scrollTo("Show 20 more") }
        each("03c-results-end") { go(Screen.Gear, Screen.GearPotential); scrollTo("About this search") }
        ui { model.graph.findGear.edit(PotentialQuery(targets = mapOf(GearStat.SPD to "24"))) }
        search()
        File(out, "result.txt").appendText("speed 24: ${model.graph.findGear.result!!.matches.size} matches, ${model.graph.findGear.result!!.matches.count { it.outcome.rareOnly }} rare-only\n")
        each("03d-results-speed24") { go(Screen.Gear, Screen.GearPotential) }
        ui { model.graph.findGear.edit(PotentialQuery()) }
        search()
        val best = r.matches.first()
        val rare = r.matches.first { it.outcome.rareOnly }
        val reforge = r.matches.firstOrNull { it.item.enhance == 15 && !it.outcome.matchesNow }
        each("04a-piece-best") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, best.item.id) }
        each("04b-piece-rare") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, rare.item.id) }
        val usual = r.matches.first { it.item.enhance < 15 && it.outcome.chance > 0 && it.outcome.chance < 1 }
        each("04d-piece-usual") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, usual.item.id) }
        if (reforge != null) each("04c-piece-reforge-only") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, reforge.item.id) }
        each("05-gear-tab-after") { go(Screen.Gear) }
        // a filter changed after the search: the list is out of date
        ui { model.graph.findGear.edit(model.graph.findGear.query.copy(slots = setOf(Slot.HELMET, Slot.NECKLACE))) }
        each("06a-stale") { go(Screen.Gear, Screen.GearPotential) }
        ui { model.graph.messages.show(model.graph.findGear.trackAll(openOverlay = false)) }
        File(out, "result.txt").appendText("track all on a stale list: screen=${model.screen} toast=${model.toast?.text}\n")
        each("06b-stale-after-track-all") { go(Screen.Gear, Screen.GearPotential) }
        // joint targets on one set and slot
        ui { model.graph.findGear.edit(PotentialQuery(targets = mapOf(GearStat.SPD to "20", GearStat.HP_P to "5"), sets = setOf("DestructionSet"), slots = setOf(Slot.HELMET))) }
        search()
        each("07a-joint-setup") { go(Screen.Gear, Screen.GearPotential); tap("Set up") }
        each("07b-joint-results") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        each("07c-joint-targets-sheet") { go(Screen.Gear, Screen.GearPotential); tap("Set up"); sheet(SheetKind.POTENTIAL_TARGETS) }
        ui { model.graph.findGear.edit(PotentialQuery(minimumChance = "30")) }
        search()
        each("08a-min-chance-30") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        ui { model.graph.findGear.edit(PotentialQuery(minimumChance = "30", show = 1)) }
        search()
        check(model.graph.findGear.result!!.matches.isEmpty()) { "the empty-state query found ${model.graph.findGear.result!!.matches.size}" }
        each("08b-empty") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        ui { model.graph.findGear.edit(PotentialQuery(show = 2)) }
        search()
        File(out, "result.txt").appendText("all gear: ${model.graph.findGear.result!!.matches.size} matches, ${model.graph.findGear.result!!.matches.count { it.outcome.matchesNow }} ready now\n")
        each("09a-all-gear") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        model.graph.findGear.result!!.matches.firstOrNull { it.outcome.matchesNow }?.let { m ->
            each("09b-piece-ready-now") { go(Screen.Gear, Screen.GearPotential); sheet(SheetKind.POTENTIAL_PIECE, m.item.id) }
        }
        // what tracking hands over to: all matches first (fresh list), then one unfinished piece, then back to the list
        ui { model.graph.findGear.edit(PotentialQuery()) }
        search()
        val first = model.graph.findGear.result!!.matches.first { it.item.enhance < 15 && it.outcome.chance > 0 }
        go(Screen.Gear, Screen.GearPotential)
        ui { model.graph.messages.show(model.graph.findGear.trackAll(openOverlay = false)) }
        File(out, "result.txt").appendText("track all on a fresh list: screen=${model.screen} queue=${model.upgrades.queue.size} toast=${model.toast?.text}\n")
        each("10a-track-all") { ui { model.sheet = null } }
        ui { OverlayState.upgrade = model.upgrades; OverlayState.expanded = true; overlay = true }
        each("10b-overlay-all")
        ui { overlay = false; OverlayState.upgrade = null }
        ui { model.graph.findGear.edit(PotentialQuery()) }
        search()
        ui { model.graph.messages.show(model.graph.findGear.track(model.graph.findGear.result!!.matches.first { it.item.id == first.item.id })) }
        File(out, "result.txt").appendText("track one: stale=${model.graph.findGear.stale} (a start-only record must not mark the list stale)\n")
        each("10c-track-one") { ui { model.sheet = null } }
        ui { OverlayState.upgrade = model.upgrades; OverlayState.expanded = true; overlay = true }
        each("10d-overlay-one")
        ui { overlay = false; OverlayState.upgrade = null }
        each("10e-back-after-tracking-one") { go(Screen.Gear, Screen.GearPotential); tap("Matches") }
        // dark theme, the main states
        ui { e7.gearscan.ui.Themes.set(ctx, true); width = 411; font = 1f }
        go(Screen.Gear, Screen.GearPotential); tap("Set up"); snap("11a-dark-setup")
        ui { model.graph.findGear.edit(PotentialQuery()) }
        search()
        go(Screen.Gear, Screen.GearPotential); tap("Matches"); snap("11b-dark-results")
        sheet(SheetKind.POTENTIAL_PIECE, first.item.id); snap("11c-dark-piece")
        ui { model.sheet = null; e7.gearscan.ui.Themes.set(ctx, false) }
    }

    @Test fun searchFilterAndTrackTargets() {
        assumeTrue("replaces account state; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var model: AppModel
        ui { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { assertTrue(model.graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); model.go(Screen.GearPotential) }
        val images = File(ctx.getExternalFilesDir(null), "audit-images")
        if (images.isDirectory) e7.gearscan.ui.GameImages.base = images.toURI().toString()
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
            }
        }
        shot("01-filters-411")
        ui { width = 353; font = 1.3f }
        shot("01-filters-353-big")
        ui { model.sheet = Sheet(SheetKind.POTENTIAL_SLOTS, 0) }
        shot("01-slots-353-big"); tap("Done")
        ui { model.sheet = Sheet(SheetKind.POTENTIAL_MAINS, 0) }
        shot("01-mains-353-big"); tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !model.graph.findGear.busy }
        assertNull(model.toast)
        val first = model.graph.findGear.result!!
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
        assertEquals(setOf("DestructionSet"), model.graph.findGear.query.sets)
        shot("03-sets-353-big")
        tap("Done")
        ui { model.graph.findGear.edit(model.graph.findGear.query.copy(slots = setOf(Slot.HELMET))) }
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !model.graph.findGear.busy }
        assertTrue(model.graph.findGear.result!!.matches.all { it.item.slot == Slot.HELMET && it.item.set == "DestructionSet" })
        assertTrue(model.graph.findGear.result!!.matches.any { kotlin.math.abs(it.outcome.chance - .25) < 1e-10 })
        shot("04-filtered-353-big")
        tap("Edit"); tap("Spd 20+"); tap("Add substat"); tap("Hp%")
        rule.onNodeWithContentDescription("Minimum Hp%").performScrollTo().performClick()
        repeat(4) { rule.onNodeWithContentDescription("Increase value").performClick() }
        rule.onNodeWithContentDescription("Step 0.1").performClick()
        repeat(5) { rule.onNodeWithContentDescription("Increase value").performClick() }
        shot("05-number-controls-353-big")
        tap("Use value")
        assertEquals("5.5", model.graph.findGear.query.targets[GearStat.HP_P])
        rule.onNodeWithContentDescription("Minimum Hp%").performClick()
        rule.onNodeWithContentDescription("Step 0.1").performClick()
        repeat(5) { rule.onNodeWithContentDescription("Decrease value").performClick() }
        tap("Use value")
        assertEquals("5", model.graph.findGear.query.targets[GearStat.HP_P])
        shot("05-joint-targets-353-big")
        tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !model.graph.findGear.busy }
        val best = model.graph.findGear.result!!.matches.first()
        assertEquals(.25, best.outcome.chance, 1e-10)
        shot("06-joint-results-353-big")
        // Empty-state feedback and minimum-chance filter.
        tap("Edit")
        tap("Finish")
        rule.onNodeWithContentDescription("Minimum chance").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Step 10").performClick()
        repeat(3) { rule.onNodeWithContentDescription("Increase value").performClick() }
        tap("Use value")
        shot("06-options-353-big"); tap("Done")
        rule.onAllNodes(hasText("Find gear") and hasClickAction()).onFirst().performTouchInput { click() }; rule.waitForIdle()
        rule.waitUntil(30_000) { !model.graph.findGear.busy }
        assertTrue(model.graph.findGear.result!!.matches.isEmpty())
        shot("07-empty-353-big")
        ui { model.graph.findGear.edit(model.graph.findGear.query.copy(minimumChance = "0")); model.graph.messages.show(model.graph.findGear.find()) }
        rule.waitUntil(30_000) { !model.graph.findGear.busy }
        tap("${model.content.set(best.item.set)?.name} ${best.item.slot.label}")
        assertEquals(SheetKind.POTENTIAL_PIECE, model.sheet!!.kind)
        shot("07-piece-353-big")

        ui { model.graph.messages.show(model.graph.findGear.track(best)) }
        assertEquals(Screen.Upgrade, model.screen)
        assertEquals(mapOf(GearStat.SPD to 20.0, GearStat.HP_P to 5.0), model.upgrades.goal!!.minimums)
        shot("08-track-selected-353-big")
        tap("Change")
        shot("08-picker-353-big")
        tap("Use selected piece")
        ui { OverlayState.upgrade = model.upgrades; OverlayState.expanded = true; overlay = true }
        assertEquals(.25, model.upgrades.goalOutcome!!.chance, 1e-10)
        shot("09-target-overlay-353-big")
        tap("Record +12")
        shot("09-editor-353-big")
        tap("Cancel")
        ui { OverlayState.expanded = false }
        shot("09-bubble-353-big")
        ui { OverlayState.expanded = true }
        // The Heroic +12 reveal leaves its final 25% Speed hit; a later miss makes Speed 20 impossible.
        ui {
            val r = model.upgrades.record!!.item
            assertEquals("Heroic", r.rank); assertEquals(9, r.enhance)
            assertTrue(model.upgrades.commit(UpgradeRecord.mainAt(r, 12), r.subs.map { it.value }, GearStat.EFF to 6.0, false))
        }
        assertEquals(.25, model.upgrades.goalOutcome!!.chance, 1e-10)
        ui {
            val r = model.upgrades.record!!.item
            assertTrue(model.upgrades.commit(UpgradeRecord.mainAt(r, 15), r.subs.map { it.value + if (it.type == GearStat.HP_P) 4 else 0 }, null, false))
        }
        assertFalse(model.upgrades.goalOutcome!!.possible)
        assertEquals("Targets no longer reachable", model.upgrades.title)
        val restored = UpgradeTracker(File(ctx.filesDir, "upgrades.json"))
        assertEquals(model.upgrades.goal, restored.goal)
        assertEquals(15, restored.record!!.item.enhance)
        shot("10-missed-target-353-big")
        val backup = JSONObject(model.graph.scan.backupText())
        assertEquals(model.graph.findGear.query, PotentialQuery.of(backup.getJSONObject("state").getJSONObject("potentialQuery")))
        ui { OverlayState.upgrade = null }
    }
}
