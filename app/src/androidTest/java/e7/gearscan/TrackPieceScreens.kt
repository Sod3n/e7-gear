package e7.gearscan

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.Finish
import e7.gearscan.overlay.OverlayUi
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The tracker over the game with build advice, on a real account (E7_FIXTURES backup.json + content.json): a piece every
 * finish of which is an upgrade, its chance and weakest helping finish, a recorded +3 (timed recheck), then +15, the reforge,
 * equipping it on the hero it helps most and Undo. Screenshots under track/ in the app's external files dir. Emulator only.
 */
class TrackPieceScreens {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "track")
    private lateinit var graph: AppGraph

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Waits for the advice ([start]: asks for it first); its time in ms. */
    private fun advise(label: String, start: Boolean = true): Long {
        val t0 = System.currentTimeMillis()
        if (start) ui { graph.tracker.checkRun.runCheck() }
        rule.waitUntil(5 * 60_000) { !graph.tracker.checkRun.busy && graph.tracker.checkRun.complete }
        return (System.currentTimeMillis() - t0).also { Log.i("TrackPieceScreens", "$label: ${it} ms, ${graph.tracker.readout.title}") }
    }

    /** The next +3 with the smallest roll on the first line: legal, and the weakest finish this piece can still reach. */
    private fun recordWeakStep(): Long {
        val r = graph.tracker.record!!
        val it = r.item
        val next = ((it.enhance / 3 + 1) * 3).coerceAtMost(15)
        val values = it.subs.mapIndexed { i, s -> if (i == 0) s.value + Finish.range(s.type, it.level).first else s.value }
        var ok = false
        ui { ok = graph.tracker.commit(UpgradeRecord.mainAt(it, next), values, null, false) }
        check(ok) { "could not record +$next: ${graph.tracker.error}" }
        // recording asks for the advice itself
        return advise("recheck after +$next", start = false)
    }

    /** A necklace that helps a hero with other unfinished necklaces: after a weak roll the tracker ranks them all. */
    @Test
    fun betterBets() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        val dir = File(ctx.getExternalFilesDir(null), "bets").also { it.deleteRecursively(); it.mkdirs() }
        rule.runOnUiThread { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        var restored = false
        ui { restored = graph.scan.restore(backup); graph.tracker.reset() }
        check(restored) { "restore failed" }
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                Box(Modifier.width(353.dp).fillMaxHeight()) { if (overlay) OverlayUi(graph, { _, _ -> }, {}, {}) else e7.gearscan.ui.App(graph) {} }
            }
        }
        fun shot(name: String) { rule.waitForIdle(); val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        fun ranked() = graph.tracker.checkRun.results.any { it.alts.size >= 2 }
        val necklaces = graph.scan.box!!.items.filter { it.slot == e7.gearscan.data.Slot.NECKLACE && it.rank == "Epic" && it.enhance in 3..9 && UpgradeRecord.supported(it) }
            .sortedByDescending { it.score }.take(30)
        val piece = necklaces.firstOrNull { p ->
            ui { graph.tracker.selectPiece(p.id) }
            advise("necklace +${p.enhance}")
            rule.waitUntil(120_000) { ranked() || graph.tracker.checkRun.results.none { it.hits.isNotEmpty() } }
            ranked()
        } ?: error("no necklace among ${necklaces.size} helps a hero with other necklaces")
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        shot("1-piece")
        recordWeakStep()
        rule.waitUntil(120_000) { ranked() }
        shot("2-after-weak-roll")
        rule.onNodeWithText("${graph.tracker.checkRun.chance}%").performClick()
        repeat(4) { i -> shot("3-advice-${i + 1}"); runCatching { rule.onNodeWithContentDescription("Next details page").performClick() } }
        val h = graph.tracker.checkRun.results.filter { it.alts.isNotEmpty() }.maxBy { it.hits.size }
        File(dir, "result.txt").writeText("${piece.slot} +${piece.enhance} → +${graph.tracker.record!!.item.enhance}: ${graph.tracker.readout.title}\n" +
            "${h.name}: better ${h.better.size}\n" + h.alts.joinToString("\n") + "\n")
        ui { graph.tracker.hide(); overlay = false }
    }

    @Test
    fun trackAPiece() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        File(ctx.filesDir, "content.json").writeText(assets.open("content.json").bufferedReader().use { it.readText() })
        val backup = assets.open("backup.json").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        rule.runOnUiThread { graph = startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        var restored = false
        ui { restored = graph.scan.restore(backup); graph.tracker.reset() }
        check(restored) { "restore failed" }
        var width by mutableStateOf(353)
        var font by mutableStateOf(1.3f)
        var overlay by mutableStateOf(false)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Box(Modifier.width(width.dp).fillMaxHeight()) { if (overlay) OverlayUi(graph, { _, _ -> }, {}, {}) else e7.gearscan.ui.App(graph) {} }
            }
        }
        fun both(name: String) { for ((w, f) in listOf(353 to 1.3f, 411 to 1f)) { ui { width = w; font = f }; shoot("$name-$w") } }

        // an Epic piece at +9 or +12 that is an upgrade for some hero whatever its rolls
        val candidates = graph.scan.box!!.items.filter { it.rank == "Epic" && it.level == 85 && it.enhance in 9..12 && UpgradeRecord.supported(it) && graph.scan.box!!.canReforge(it.id) }
            .sortedByDescending { it.score }.take(40)
        var first = 0L
        val piece = candidates.firstOrNull { p ->
            ui { graph.tracker.selectPiece(p.id) }
            first = advise("first check ${p.slot} +${p.enhance}")
            graph.tracker.checkRun.results.any { it.hits.isNotEmpty() && it.hits.size == it.total }
        } ?: error("no piece among ${candidates.size} is an upgrade whatever its rolls")
        val hero = graph.tracker.checkRun.results.first { it.hits.size == it.total && it.total > 0 }
        ui { graph.tracker.show(); graph.overlay.expanded = true; overlay = true }
        both("1-piece")
        rule.onNodeWithText("${graph.tracker.checkRun.chance}%").performClick()
        both("2-advice")
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Final build").performClick()
        both("2b-final-build")
        rule.onNodeWithText("Back").performClick()

        val recheck = recordWeakStep()
        both("3-after-record")
        while (graph.tracker.record!!.item.enhance < 15) recordWeakStep()
        both("4-finished")
        ui { overlay = false; graph.nav.go(Screen.Upgrade) }
        both("4b-app-finished")
        ui { overlay = true }
        rule.onNodeWithContentDescription("Record reforge").performClick()
        rule.waitUntil(60_000) { graph.tracker.record!!.item.level == 90 && !graph.tracker.checkRun.busy && graph.tracker.checkRun.complete }
        both("5-reforged")
        check(graph.tracker.checkRun.equipHeroes.any { it.id == hero.id }) { "${hero.name} isn't offered after the reforge" }
        rule.onNodeWithContentDescription("Record equip").performClick()
        both("6-equip-choices")
        val target = graph.tracker.checkRun.equipHeroes.first()
        rule.onNodeWithText("${target.name} · ${target.nowGain}").performClick()
        rule.waitForIdle()
        val worn = graph.scan.box!!.gearOf(target.id)[piece.slot]
        check(worn?.id == piece.id && worn.level == 90) { "the box doesn't show the reforged piece on ${target.name}" }
        both("7-equipped")
        ui { overlay = false }
        both("7b-app-equipped")
        ui { overlay = true }
        rule.onNodeWithContentDescription("More").performClick(); rule.waitForIdle()
        rule.onNodeWithContentDescription("Undo equip").performClick()
        rule.waitForIdle()
        val back = graph.scan.box!!.itemsById.getValue(piece.id)
        check(back.level == 85 && back.enhance == piece.enhance && back.equippedOn == piece.equippedOn) { "Undo didn't restore the scanned piece" }
        File(shots, "result.txt").writeText("piece ${piece.slot} +${piece.enhance}: first check $first ms, recheck after +3 $recheck ms; " +
            "hero ${hero.name} ${hero.hits.size}/${hero.total}; equipped on ${target.name} (${target.nowGain}) and undone\n")
        ui { graph.tracker.hide(); overlay = false }
    }
}
