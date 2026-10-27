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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.Slot
import e7.gearscan.overlay.OverlayState
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
        ui { restored = model.restore(backup) }
        check(restored) { "restore failed" }
        var width by mutableStateOf(353)
        var font by mutableStateOf(1.3f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {} }
            }
        }

        // a hero whose worn build counts a finished, not yet reforged level 85 piece as reforged
        val box = model.box!!
        val hero = model.heroUpgradeHeroes().first { h -> model.request(h.id).reforged && box.gearOf(h.id).size == 6 &&
            box.gearOf(h.id).values.any { it.reforgeable && box.canReforge(it.id) } }
        val build = model.currentBuild(hero.id)!!
        val toReforge = build.items.filter { it.reforgeable && box.canReforge(it.id) }.map { it.id }.toSet()
        ui { model.loadBuildChecklist(hero.id, build, "Reforge check"); OverlayState.expanded = true; overlay = true }
        val steps = OverlayState.build!!.pieces
        check(steps.filter { it.reforgeStep }.map { it.item.id }.toSet() == toReforge) { "reforge steps ${steps.filter { it.reforgeStep }.map { it.item.id }} vs $toReforge" }
        // equipping comes first, then reforging, then modifying
        check(steps.map { it.menu } == steps.map { it.menu }.sorted()) { "steps out of menu order" }
        for ((w, f) in listOf(353 to 1.3f, 411 to 1f)) { ui { width = w; font = f }; shoot("1-checklist-$w") }
        ui { OverlayState.focus = steps.indexOfFirst { it.reforgeStep } }
        shoot("2-reforge-step-open-353")

        // finishing records the reforges into the box; Undo takes them back
        ui { for (i in steps.indices) OverlayState.done[i] = true; OverlayState.finish(); overlay = false }
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
        ui { OverlayState.build = null }
    }
}
