package e7.gearscan

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
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

    @Test fun searchFilterAndTrackTargets() {
        assumeTrue("replaces account state; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = inst.context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        lateinit var model: AppModel
        ui { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { assertTrue(model.restore(assets.open("backup.json").bufferedReader().use { it.readText() })); model.go(Screen.GearPotential) }
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
        tap("Find gear")
        rule.waitUntil(30_000) { !model.potentialBusy }
        assertNull(model.potentialError)
        val first = model.potentialResult!!
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
        assertEquals(setOf("DestructionSet"), model.potentialQuery.sets)
        shot("03-sets-353-big")
        tap("Done")
        ui { model.updatePotentialQuery(model.potentialQuery.copy(slots = setOf(Slot.HELMET))) }
        tap("Find gear")
        rule.waitUntil(30_000) { !model.potentialBusy }
        assertTrue(model.potentialResult!!.matches.all { it.item.slot == Slot.HELMET && it.item.set == "DestructionSet" })
        assertTrue(model.potentialResult!!.matches.any { kotlin.math.abs(it.outcome.chance - .25) < 1e-10 })
        shot("04-filtered-353-big")
        tap("Edit"); tap("Spd 20+"); tap("Add substat"); tap("Hp%")
        rule.onNodeWithContentDescription("Minimum Hp%").performScrollTo().performClick()
        repeat(4) { rule.onNodeWithContentDescription("Increase value").performClick() }
        rule.onNodeWithContentDescription("Step 0.1").performClick()
        repeat(5) { rule.onNodeWithContentDescription("Increase value").performClick() }
        shot("05-number-controls-353-big")
        tap("Use value")
        assertEquals("5.5", model.potentialQuery.targets[GearStat.HP_P])
        rule.onNodeWithContentDescription("Minimum Hp%").performClick()
        rule.onNodeWithContentDescription("Step 0.1").performClick()
        repeat(5) { rule.onNodeWithContentDescription("Decrease value").performClick() }
        tap("Use value")
        assertEquals("5", model.potentialQuery.targets[GearStat.HP_P])
        shot("05-joint-targets-353-big")
        tap("Done")
        tap("Find gear")
        rule.waitUntil(30_000) { !model.potentialBusy }
        val best = model.potentialResult!!.matches.first()
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
        tap("Find gear")
        rule.waitUntil(30_000) { !model.potentialBusy }
        assertTrue(model.potentialResult!!.matches.isEmpty())
        shot("07-empty-353-big")
        ui { model.updatePotentialQuery(model.potentialQuery.copy(minimumChance = "0")); model.findGearPotential() }
        rule.waitUntil(30_000) { !model.potentialBusy }
        tap("${model.content.set(best.item.set)?.name} ${best.item.slot.label}")
        assertEquals(SheetKind.POTENTIAL_PIECE, model.sheet!!.kind)
        shot("07-piece-353-big")
        ui { model.sheet = Sheet(SheetKind.INVENTORY_ORDER, 0) }
        shot("07-inventory-353-big")
        tap("High score first"); assertFalse(model.inventoryAscending)
        tap("In bag only"); assertTrue(model.inventoryBagOnly)
        tap("Low score first"); tap("All gear"); tap("Done")
        ui { model.trackPotential(best) }
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
        val backup = JSONObject(model.backupText())
        assertEquals(model.potentialQuery, PotentialQuery.of(backup.getJSONObject("state").getJSONObject("potentialQuery")))
        ui { OverlayState.upgrade = null }
    }
}
