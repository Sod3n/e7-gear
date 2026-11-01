package e7.gearscan

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.ui.App
import e7.gearscan.ui.Themes
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Improve heroes on a real account, for review: restores E7_FIXTURES' backup.json (content.json stands in for the download),
 * runs the check, stops it part way and continues, then saves every state at two widths and two font sizes, plus the
 * results in the dark theme, to the app's external files dir under heroes/. A fresh model must read the saved result back.
 */
class HeroUpgradeScreens {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "heroes")
    private lateinit var model: AppModel

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Design rounds: the results states only, from a finished check saved as E7_FIXTURES/hero_upgrades.json, in about a
     * minute instead of running the check twice. Run with -Pandroid.testInstrumentationRunnerArguments.class=e7.gearscan.HeroUpgradeScreens#quickRender
     */
    @Test
    fun quickRender() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val saved = runCatching { assets.open("hero_upgrades.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("no saved check in E7_FIXTURES (hero_upgrades.json)", saved != null)
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        File(ctx.filesDir, "hero_upgrades.json").writeText(saved!!)
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { check(model.restore(backup)); model.consent = true; Themes.set(ctx, false) }
        File(ctx.filesDir, "hero_upgrades.json").takeIf { !it.exists() }?.writeText(saved)
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        val r = checkNotNull(model.heroUpgrades?.result) { "the saved check didn't load" }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { App(model) {} }
            }
        }
        val hero = model.heroUpgradeView(r).first { it.rows.isNotEmpty() }
        val row = hero.rows.first()
        // the stat chips are worked out in the background: shoot once they are in
        ui { model.sheet = null; model.stack = listOf(Screen.Gear, Screen.HeroUpgrades()) }
        val tops = model.heroUpgradeView(r).mapNotNull { h -> h.rows.firstOrNull()?.let { Triple(h.hero.id, it.pick.piece, model.heroUpgradeRebuilds) } }
        rule.waitUntil(60_000) { tops.all { it in model.heroUpgradeChanges } }
        for ((w, f) in listOf(411 to 1f, 353 to 1.3f)) {
            ui { width = w; font = f }
            val tag = "$w-${if (f > 1f) "big" else "1x"}"
            ui { model.sheet = null; model.stack = listOf(Screen.Gear, Screen.HeroUpgrades()) }; shoot("3-by-hero-$tag")
            rule.onAllNodesWithText("By piece", useUnmergedTree = true)[0].performTouchInput { click() }; shoot("3d-by-piece-$tag")
            rule.onAllNodesWithText("By hero", useUnmergedTree = true)[0].performTouchInput { click() }
            ui { model.stack = listOf(Screen.Gear, Screen.HeroUpgrades(), Screen.HeroUpgrades(hero.hero.id)) }; shoot("4-hero-$tag")
            ui { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, row.pick.piece) }; shoot("5-piece-$tag")
            ui { model.openFinalBuild(hero.hero.id, row); model.sheet = Sheet(SheetKind.HERO_UPGRADE_BUILD, row.pick.piece) }
            rule.waitUntil(60_000) { !model.finalBuildBusy }; shoot("5b-final-build-$tag")
            ui { model.sheet = null }
        }
    }

    @Test
    fun improveHeroes() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        check(model.content.heroes.isNotEmpty()) { "no game content" }
        var ok = false
        ui { ok = model.restore(backup); model.consent = true; Themes.set(ctx, false) }
        check(ok) { "restore failed" }
        File(ctx.filesDir, "hero_upgrades.json").delete(); File(ctx.filesDir, "hero_upgrades_progress.json").delete()
        ui { model.heroUpgrades = null }
        var width by mutableStateOf(411)
        var font by mutableStateOf(1f)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { App(model) {} }
            }
        }
        val setups = listOf(411 to 1f, 353 to 1.3f)
        fun top() = repeat(30) { rule.onRoot().performTouchInput { swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = 80) } }
        fun scrollTo(text: String) = runCatching {
            rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[0].performScrollTo(); rule.waitForIdle() }.isSuccess
        // a swipe down closes an open sheet, so sheets are shot as they open
        fun each(name: String, sheet: Boolean = false, at: () -> Unit = {}) {
            for ((w, f) in setups) {
                ui { width = w; font = f }
                if (!sheet) top()
                at()
                shoot("$name-$w-${if (f > 1f) "big" else "1x"}")
            }
        }
        ui { model.go(Screen.ScanResult) }
        each("0-imported")
        ui { model.go(Screen.HeroUpgrades()) }
        each("1-intro")
        ui { model.startHeroUpgrades() }
        rule.waitUntil(5 * 60_000) { (model.heroUpgrades?.done ?: 0) > 0 }
        each("2-running")
        rule.waitUntil(20 * 60_000) { model.heroUpgrades.let { it != null && it.total > 0 && it.done * 10 >= it.total * 3 } }
        ui { model.stopHeroUpgrades() }
        check(model.heroUpgradesResumable) { "a stopped check can't be continued" }
        each("2b-stopped")
        val t0 = System.currentTimeMillis()
        ui { model.startHeroUpgrades() }
        rule.waitUntil(30 * 60_000) { model.heroUpgrades?.running == false }
        val st = model.heroUpgrades!!
        val r = checkNotNull(st.result) { "the continued check gave no result" }
        val view = model.heroUpgradeView(r)
        val line = "resumed check: ${view.count { it.rows.isNotEmpty() }} of ${view.size} heroes can get better, ${r.picks.size} piece/hero pairs, " +
            "${r.unknownSteps} open steps, ${(System.currentTimeMillis() - t0) / 1000} s"
        Log.i("HeroUpgradeScreens", line)
        File(shots, "result.txt").writeText(line + "\n" + view.joinToString("\n") { h ->
            "${model.heroName(h.hero)} t${model.tier(h.hero.id)} now=${h.bestNow} rows=${h.rows.size} " +
                h.rows.take(3).joinToString { "${it.pick.piece} ${it.chance}% ${it.typical}/${it.best}" } })
        check(!model.heroUpgradesResumable) { "a finished check still offers to continue" }
        check(!model.heroUpgradesStale) { "a fresh result reads as stale" }

        // a restarted app reads the result back
        var again: AppModel? = null
        rule.runOnUiThread { again = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { again!!.ready }
        check(again!!.heroUpgrades?.result?.picks?.size == r.picks.size) { "the saved result didn't come back" }
        check(!again!!.heroUpgradesStale) { "the saved result reads as stale after a restart" }

        each("3-by-hero")
        each("3b-nothing-to-gain") { scrollTo("Nothing to gain now") }
        ui { model.useHeroUpgradeMinGain(1.0) }
        each("3c-one-roll")
        ui { model.useHeroUpgradeMinGain(0.5) }
        for ((w, f) in setups) {
            ui { width = w; font = f }
            top()
            rule.onAllNodesWithText("By piece", useUnmergedTree = true)[0].performTouchInput { click() }
            rule.waitForIdle()
            shoot("3d-by-piece-$w-${if (f > 1f) "big" else "1x"}")
            rule.onAllNodesWithText("By hero", useUnmergedTree = true)[0].performTouchInput { click() }
            rule.waitForIdle()
        }
        ui { model.useHeroUpgradeRebuilds(true) }
        each("3e-rebuilds")
        val rebuildView = model.heroUpgradeView(r)
        val rebuildHero = rebuildView.first { h -> h.rows.any { it.pick.sets.isNotEmpty() } }
        ui { model.go(Screen.HeroUpgrades(rebuildHero.hero.id)) }
        each("3f-rebuild-hero")
        val rebuildRow = rebuildHero.rows.first { it.pick.sets.isNotEmpty() }
        ui { model.openFinalBuild(rebuildHero.hero.id, rebuildRow); model.sheet = Sheet(SheetKind.HERO_UPGRADE_BUILD, rebuildRow.pick.piece) }
        rule.waitUntil(60_000) { !model.finalBuildBusy }
        check(model.finalBuild != null) { "no final build for a rebuild pick" }
        each("3g-final-build-rebuild", sheet = true)
        ui { model.sheet = null; model.useHeroUpgradeRebuilds(false); model.go(Screen.HeroUpgrades()) }
        val hero = model.heroUpgradeView(r).first { it.rows.isNotEmpty() }
        ui { model.go(Screen.HeroUpgrades(hero.hero.id)) }
        each("4-hero")
        ui { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, hero.rows.first().pick.piece) }
        each("5-piece", sheet = true)
        ui { model.openFinalBuild(hero.hero.id, hero.rows.first()); model.sheet = Sheet(SheetKind.HERO_UPGRADE_BUILD, hero.rows.first().pick.piece) }
        rule.waitUntil(60_000) { !model.finalBuildBusy }
        val fb = checkNotNull(model.finalBuild) { "no final build" }
        // this piece only: every other piece is the one the hero's best build already has
        check(fb.from.count { it == "This piece, upgraded" } == 1) { "final build: ${fb.from}" }
        each("5b-final-build", sheet = true)
        ui { model.sheet = Sheet(SheetKind.HERO_UPGRADE_HELP, 0) }
        each("6-help", sheet = true)
        ui { model.sheet = null; model.go(Screen.HeroDetail(hero.hero.id)) }
        each("7-hero-detail") { scrollTo("Upgrades") }
        ui { model.go(Screen.HeroUpgrades()) }
        for (dark in listOf(true)) {
            ui { Themes.set(ctx, dark); width = 411; font = 1f }
            top(); shoot("8-dark-by-hero")
            ui { model.go(Screen.HeroUpgrades(hero.hero.id)) }
            top(); shoot("8-dark-hero")
            ui { Themes.set(ctx, false) }
        }
        ui { model.trackHeroUpgrades(hero.rows.map { it.pick.piece }) }
        check(model.upgrades.queue.size == hero.rows.size) { "the tracker list has ${model.upgrades.queue.size} of ${hero.rows.size} pieces" }
        check(model.upgrades.queueSource == "heroes") { "the tracker doesn't know the list came from Improve heroes" }
        e7.gearscan.overlay.OverlayService.stop(ctx)
        ui { model.go(Screen.Upgrade) }
        each("9-tracker")
    }
}
