package e7.gearscan

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import e7.gearscan.data.GearStat
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.Slot
import e7.gearscan.overlay.OverlayUi
import e7.gearscan.ui.App
import e7.gearscan.ui.PINNED_BACK_TAG
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * On a real account (E7_FIXTURES backup.json + content.json): the equip checklist's reforge steps (one per finished level 85
 * piece the build counts as reforged), recording them at the end and Undo; and the back link pinned once it scrolls away.
 * Screenshots under reforge/ in the app's external files dir. Emulator only.
 */
class ReforgeStepScreens {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "reforge")
    private lateinit var model: AppModel

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun reforgeStepsAndPinnedBack() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        var restored = false
        ui { restored = model.graph.scan.restore(backup) }
        check(restored) { "restore failed" }
        var width by mutableStateOf(353)
        var font by mutableStateOf(1.3f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi(model.graph, { _, _ -> }, {}, {}) else App(model) {} }
            }
        }

        // a hero whose worn build counts a finished, not yet reforged level 85 piece as reforged
        val box = model.box!!
        val hero = model.graph.improveHeroes.heroes().first { h -> model.graph.heroes.request(h.id).reforged && box.gearOf(h.id).size == 6 &&
            box.gearOf(h.id).values.any { it.reforgeable && box.canReforge(it.id) } }
        val build = model.graph.heroes.currentBuild(hero.id)!!
        val toReforge = build.items.filter { it.reforgeable && box.canReforge(it.id) }.map { it.id }.toSet()
        ui { model.loadBuildChecklist(hero.id, build, "Reforge check"); model.graph.overlay.expanded = true; overlay = true }
        val steps = model.graph.checklist.build!!.pieces
        check(steps.filter { it.reforgeStep }.map { it.item.id }.toSet() == toReforge) { "reforge steps ${steps.filter { it.reforgeStep }.map { it.item.id }} vs $toReforge" }
        // equipping comes first, then reforging, then modifying
        check(steps.map { it.menu } == steps.map { it.menu }.sorted()) { "steps out of menu order" }
        for ((w, f) in listOf(353 to 1.3f, 411 to 1f)) { ui { width = w; font = f }; shoot("1-checklist-$w") }
        ui { model.graph.checklist.focus = steps.indexOfFirst { it.reforgeStep } }
        shoot("2-reforge-step-open-353")

        // finishing records the reforges into the box; Undo takes them back
        ui { for (i in steps.indices) model.graph.checklist.done[i] = true; model.graph.checklist.finish(); overlay = false }
        val after = model.box!!
        check(toReforge.all { after.itemsById.getValue(it).level == 90 }) { "reforged pieces not level 90 after finishing" }
        check(toReforge.all { after.itemsById.getValue(it).equippedOn == hero.id }) { "reforged pieces left the hero" }
        val toast = checkNotNull(model.toast) { "no toast after recording" }
        check(toast.text.contains("reforged")) { "toast: ${toast.text}" }
        ui { toast.onAction?.invoke() }
        check(toReforge.all { model.box!!.itemsById.getValue(it).level == 85 }) { "Undo didn't restore the reforged pieces" }

        // the back link stays reachable: pinned once its place scrolls away
        ui { model.go(Screen.ScanData) }
        check(rule.onAllNodesWithTag(PINNED_BACK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { "pinned back shown before scrolling" }
        for ((w, f) in listOf(353 to 1.3f, 411 to 1f)) { ui { width = w; font = f }; shoot("3-top-$w") }
        repeat(6) { rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f, durationMillis = 300) } }
        rule.waitForIdle()
        check(rule.onAllNodesWithTag(PINNED_BACK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "no pinned back after scrolling" }
        for ((w, f) in listOf(353 to 1.3f, 411 to 1f)) { ui { width = w; font = f }; shoot("4-scrolled-$w") }
        val depth = model.stack.size
        rule.onAllNodesWithTag(PINNED_BACK_TAG, useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
        check(model.stack.size == depth - 1) { "pinned back didn't go back" }
        File(shots, "result.txt").writeText("hero ${hero.name}: ${toReforge.size} reforge steps of ${steps.size}; pinned back ok\n")
        ui { model.graph.checklist.unload() }
    }

    /** A modification step asks what the game rolled; finishing writes that substat into the box, Undo takes it back. */
    @Test
    fun modStepRecordsTheRoll() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        val dir = File(ctx.getExternalFilesDir(null), "mods").also { it.deleteRecursively(); it.mkdirs() }
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        var restored = false
        ui { restored = model.graph.scan.restore(backup) }
        check(restored) { "restore failed" }
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                Box(Modifier.width(353.dp).fillMaxHeight()) { if (overlay) OverlayUi(model.graph, { _, _ -> }, {}, {}) else App(model) {} }
            }
        }
        fun shot(name: String) { rule.waitForIdle(); val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        // a worn +15 piece turned into crit damage (or another stat it lacks), the way a search with mods would
        val box = model.box!!
        val hero = model.graph.improveHeroes.heroes().first { h -> box.gearOf(h.id).size == 6 && model.graph.heroes.currentBuild(h.id) != null }
        val cur = model.graph.heroes.currentBuild(hero.id)!!
        val modded = cur.items.firstNotNullOfOrNull { it ->
            val want = listOf(GearStat.CD, GearStat.CR, GearStat.SPD, GearStat.ATK_P, GearStat.HP_P).filter { t -> it.subs.none { s -> s.type == t } }.toSet()
            e7.gearscan.opt.Mods.variants(it, want, { true }, 6, true, 0.5, false).firstOrNull()
        } ?: error("no worn +15 piece of ${hero.name} can be modded")
        val m = modded.mod!!
        val build = e7.gearscan.opt.Build(cur.items.map { if (it.id == modded.id) modded else it }, cur.value, cur.final, cur.ratings, cur.fromOthers)
        val original = box.itemsById.getValue(modded.id).subs[m.index]

        ui { model.loadBuildChecklist(hero.id, build, "Mod check"); model.graph.overlay.expanded = true; overlay = true }
        val at = model.graph.checklist.build!!.pieces.indexOfFirst { it.modStep }
        check(at >= 0) { "no mod step" }
        shot("1-checklist-before-mod-step")
        ui { model.graph.checklist.focus = at }
        shot("2-mod-step")
        rule.onNodeWithContentDescription("Rolled value up").performClick()
        rule.onNodeWithContentDescription("Rolled value up").performClick()
        rule.waitForIdle()
        val range = e7.gearscan.opt.Mods.range(m.to, model.graph.checklist.build!!.pieces[at].orig.subs[m.index].rolls)
        val want = (m.toValue + 2).coerceAtMost(range.last.toDouble())
        check(model.graph.checklist.rolledValue(at) == want) { "rolled ${model.graph.checklist.rolledValue(at)}, wanted $want" }
        shot("3-rolled-set")
        ui { for (i in model.graph.checklist.done.indices) model.graph.checklist.done[i] = true; model.graph.checklist.finish(); overlay = false }
        val written = model.box!!.itemsById.getValue(modded.id).subs[m.index]
        check(written.type == m.to && written.value == want && written.modified) { "box has ${written.type} ${written.value} modified=${written.modified}" }
        val toast = checkNotNull(model.toast) { "no toast after recording" }
        check(toast.text.contains("1 modified") && !toast.text.contains("planned")) { "toast: ${toast.text}" }
        shot("4-recorded")
        ui { toast.onAction?.invoke() }
        val undone = model.box!!.itemsById.getValue(modded.id).subs[m.index]
        check(undone.type == original.type && undone.value == original.value) { "Undo left ${undone.type} ${undone.value}" }

        // Equip in app: no roll was asked, so the planned value goes in and the toast says so
        ui { model.graph.heroes.recordBuild(hero.id, build) }
        val planned = model.box!!.itemsById.getValue(modded.id).subs[m.index]
        check(planned.type == m.to && planned.value == m.toValue) { "Equip in app wrote ${planned.type} ${planned.value}" }
        check(model.toast!!.text.contains("planned values")) { "toast: ${model.toast!!.text}" }
        ui { model.toast!!.onAction?.invoke() }
        check(model.box!!.itemsById.getValue(modded.id).subs[m.index].value == original.value) { "Undo after Equip in app" }
        File(dir, "result.txt").writeText("${hero.name}: ${modded.slot} ${m.from.short} ${m.fromValue} -> ${m.to.short} planned ${m.toValue}, rolled $want; " +
            "recorded, undone; Equip in app wrote the planned value\n")
        ui { model.graph.checklist.unload() }
    }
}
