package e7.gearscan

import e7.gearscan.features.sellreview.trackMatches
import e7.gearscan.ui.trackMatch
import e7.gearscan.features.optimize.SearchState
import e7.gearscan.features.scan.CaptureSession
import e7.gearscan.features.scan.ScanError
import e7.gearscan.features.scan.ScanPhase
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.PotentialQuery
import e7.gearscan.data.Problems
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.overlay.OverlayUi
import e7.gearscan.ui.App
import e7.gearscan.ui.installImageLoader
import e7.gearscan.ui.trackAllMatches
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Renders every screen, sheet and the over-game panel on a real box in several phone setups and
 * fails on: crashes, text broken inside a word, text clipped without "…", text past the right
 * edge, images that fail to load, and data gaps the app logs to [Problems] (unknown sets, …).
 * Screenshots of every state land in the app's external files dir under audit/ for review.
 *
 * Emulator only: it replaces the app's box with the fixture. Run through tools/audit.sh.
 */
class ScreenAudit {
    @get:Rule val rule = createComposeRule()

    private data class Setup(val name: String, val widthDp: Int, val fontScale: Float, val locale: Locale)

    private val setups = listOf(
        Setup("353-ru", 353, 1f, Locale("ru", "RU")),
        Setup("411-en", 411, 1f, Locale.US),
        Setup("353-big-en", 353, 1.3f, Locale.US),
        Setup("411-big-ru", 411, 1.3f, Locale("ru", "RU")),
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val found = linkedMapOf<String, MutableSet<String>>()
    private val warnings = linkedMapOf<String, MutableSet<String>>()
    private val shots = File(ctx.getExternalFilesDir(null), "audit")

    @Test
    fun everyScreen() {
        var boxAside: e7.gearscan.data.Box? = null
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val gear = InstrumentationRegistry.getInstrumentation().context.assets.open("gear.txt").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        // the audit clears app data first, which takes back the notification permission a player grants on first launch
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)

        // offline when the fixture folder carries them: game content and hero builds fetched by an earlier run
        val fixtureAssets = InstrumentationRegistry.getInstrumentation().context.assets
        runCatching { fixtureAssets.open("content.json").use { i -> File(ctx.filesDir, "content.json").outputStream().use { i.copyTo(it) } } }
        runCatching { fixtureAssets.list("builds")?.forEach { n -> File(ctx.filesDir, "builds").mkdirs()
            fixtureAssets.open("builds/$n").use { i -> File(ctx.filesDir, "builds/$n").outputStream().use { i.copyTo(it) } } } }
        var setup by mutableStateOf(setups[0])
        var overlay by mutableStateOf(false)
        rule.runOnUiThread { graph = startedGraph(ctx) }
        installImageLoader(ctx, graph.http, graph.content.mirrorSwitch)
        // never call Fribbels from a test: builds only from the fixture cache, fixed targets otherwise
        rule.runOnUiThread { graph.heroes.presetNetwork = false }
        rule.waitUntil(20_000) { graph.ready }
        // the APK carries no game data: the first start downloads it from the content site
        rule.waitUntil(60_000) { graph.content.content.heroes.isNotEmpty() }
        rule.runOnUiThread { graph.scan.import(gear); graph.settings.consent = true }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, setup.fontScale)) {
                Box(Modifier.width(setup.widthDp.dp).fillMaxHeight()) {
                    if (overlay) OverlayUi(graph, { _, _ -> }, {}, {}) else App(graph) {}
                }
            }
        }
        heroNames = graph.scan.box!!.heroes.map { graph.heroes.heroName(it) }.filter { it.length > 3 }.toSet()
        check(graph.scan.box!!.heroes.any { graph.heroes.gameHero(it) != null && graph.scan.box!!.gearOf(it.id).size == 6 }) {
            "no fully geared known hero: box ${graph.scan.box!!.heroes.size} heroes, ${graph.scan.box!!.heroes.count { graph.heroes.gameHero(it) != null }} known; content ${graph.content.content.heroes.size} heroes" }
        val hero = graph.scan.box!!.heroes.first { graph.heroes.gameHero(it) != null && graph.scan.box!!.gearOf(it.id).size == 6 }.id
        val team = graph.scan.box!!.heroes.filter { graph.heroes.gameHero(it) != null && graph.scan.box!!.gearOf(it.id).size == 6 }.take(3).map { it.id }
        ui {
            graph.heroes.tiers = graph.scan.box!!.heroes.take(40).withIndex().associate { (i, h) -> h.id to 5 - i % 5 } + team.withIndex().associate { (i, id) -> id to 5 - i }
            graph.heroes.pickHero(hero)
        }
        // presets come from the hero library over the network; the search must run with the one that gets applied
        runCatching { rule.waitUntil(10_000) { graph.heroes.request(hero).targets.any { !it.isNaN() } } }
        if (graph.heroes.request(hero).targets.all { it.isNaN() }) ui { graph.heroes.request(hero).targets[Stat.SPD.ordinal] = 200.0; graph.rev.bump() }
        rule.waitForIdle()
        // searches are slow on an emulator; run each once and show the same result in every setup
        search(graph, listOf(hero)); val single = graph.optimize.search
        ui { graph.heroes.team = listOf(hero); graph.heroes.pickHero(hero) }
        // match % must read in the same order as the ranking, or "#3 72% above #4 75%" looks like a bug
        if (graph.heroes.request(hero).goal == e7.gearscan.data.Goal.TARGETS) {
            val pcts = single!!.builds.mapNotNull { graph.heroes.matchOf(hero, it)?.pct }
            if (pcts.zipWithNext().any { (a, b) -> b > a }) add("results out of match order", pcts.take(10).joinToString())
        }

        // a real pool run over the tier-5 heroes, once; screens below reuse its state
        ui { graph.pool.tiers = setOf(5); graph.pool.extra = emptySet(); graph.pool.heroes().forEach { graph.heroes.loadPresetsQuietly(it.id, network = true) } }
        rule.waitUntil(60_000) { graph.pool.heroes().none { graph.heroes.hasBuild(it.id) == null } }
        // community builds come from Fribbels' service, which can refuse requests (403 after a burst); heroes without
        // one get fixed test targets so the pool run always has several heroes, network or not
        ui { graph.pool.heroes().filter { graph.heroes.hasBuild(it.id) != true }.forEach { h -> graph.heroes.request(h.id).goal = e7.gearscan.data.Goal.TARGETS
            graph.heroes.request(h.id).targets[Stat.SPD.ordinal] = 200.0; graph.rev.bump() } }
        check(graph.pool.heroes().size >= 2) { "the pool run needs two heroes; tier-5 heroes: ${graph.pool.heroes().size}" }
        val poolT0 = System.currentTimeMillis()
        ui { graph.pool.start() }
        // the run keeps going in the game: a foreground service shows it, and leaves when it ends
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        fun runNotice() = nm.activeNotifications.any { it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "Optimizing pool" }
        runCatching { rule.waitUntil(5_000) { runNotice() || graph.pool.state?.running == false } }
        if (graph.pool.state?.running == true && !runNotice()) add("no notification while the pool runs", "pool service")
        rule.waitUntil(600_000) { graph.pool.state?.running == false }
        runCatching { rule.waitUntil(3_000) { !runNotice() } }
        if (runNotice()) add("the run notification stays after the run ends", "pool service")
        val poolDone = graph.pool.state!!
        Log.i("ScreenAudit", "pool: ${poolDone.ids.size} heroes in ${(System.currentTimeMillis() - poolT0) / 1000} s, ${poolDone.moves} moves, " +
            "score ${graph.pool.score(poolDone.before)} -> ${graph.pool.score(poolDone.ids.associateWith { poolDone.after[it] ?: poolDone.before[it] })}")
        if (poolDone.builds.isEmpty()) add("pool", "the pool run produced no builds")
        if (graph.pool.pending != poolDone.startedAt) add("a finished pool run isn't pending", "pool pending")
        // history: the run and the earlier one-hero search are recorded, and stored builds come back as they were
        run {
            val e = graph.history.entries.firstOrNull { it.pool && it.at == poolDone.startedAt }
            if (e == null) add("a finished pool run is not in history", "history")
            else for ((id, b) in poolDone.builds) {
                if (b == null) continue
                val back = e.builds[id]?.firstOrNull()?.let { graph.history.restoreBuild(id, it) }
                if (back?.items?.map { it.id to it.mod?.to } != b.items.filter { !it.ghost }.map { it.id to it.mod?.to }) add("a stored pool build comes back different", "history: ${graph.heroes.hero(id)?.let(graph.heroes::heroName)}")
            }
            if (graph.history.entries.none { !it.pool }) add("a finished one-hero search is not in history", "history")
        }
        // Never worse: A wants speed from B, who wears its fastest build; B keeps what it can't spare and ends no slower
        var tradesPool: e7.gearscan.features.pool.PoolState? = null
        run {
            // B wears the run's fastest boots (or is its fastest hero, when none wears speed boots) and A chases speed
            fun bootSpeed(id: Long) = graph.scan.box!!.gearOf(id)[e7.gearscan.data.Slot.BOOTS]?.takeIf { it.mainType == e7.gearscan.data.GearStat.SPD }?.mainValue ?: 0.0
            val b = poolDone.ids.maxBy { bootSpeed(it) * 1000 + (graph.heroes.currentBuild(it)?.final?.get(Stat.SPD) ?: 0.0) }
            val a = poolDone.ids.first { it != b }
            Log.i("ScreenAudit", "never worse: B wears speed boots ${bootSpeed(b)}")
            val ra = graph.heroes.request(a).copy(); val rb = graph.heroes.request(b).copy()
            ui { graph.heroes.request(b).goal = e7.gearscan.data.Goal.SPEED; graph.heroes.request(b).lowerPriorityOnly = false }
            val keepSearch = graph.optimize.search
            search(graph, listOf(b))
            val fastest = graph.optimize.search?.builds?.firstOrNull()
            val undo = fastest?.let { f -> var prev: Map<Long, Long?> = emptyMap(); ui { prev = graph.heroes.equip(b, f) }; prev }
            val bNow = graph.heroes.currentBuild(b)
            ui {
                graph.heroes.request(a).goal = e7.gearscan.data.Goal.SPEED
                graph.heroes.request(a).lowerPriorityOnly = false
                graph.pool.start()
            }
            rule.waitUntil(600_000) { graph.pool.state?.running == false }
            val p = graph.pool.state!!
            tradesPool = p
            val notMade = p.trades.filter { !it.made }
            Log.i("ScreenAudit", "never worse: ${notMade.size} trades not made, moves ${p.moves}, B speed ${bNow?.final?.get(Stat.SPD)} -> ${p.builds[b]?.final?.get(Stat.SPD)}")
            if (notMade.isEmpty()) add("the speed conflict made no trade fail, so Never worse went untested", "pool")
            if ((p.builds[b]?.final?.get(Stat.SPD) ?: bNow!!.final[Stat.SPD]) < bNow!!.final[Stat.SPD] - 1e-3) add("Never worse: the Max speed hero ends slower", "pool")
            for (id in p.ids) if ((p.after[id] ?: 0) < (p.before[id] ?: 0)) add("Never worse: a hero's match drops", "pool: ${graph.heroes.hero(id)?.let(graph.heroes::heroName)} ${p.before[id]} → ${p.after[id]}")
            if (notMade.any { t -> p.builds[t.taker]?.items?.any { it.id in t.items } == true }) add("a hero got a piece listed as not traded", "pool")
            // overruling: a trade not made, given by the player, happens on the re-run; a piece kept stays
            notMade.firstOrNull()?.let { t ->
                ui { graph.pool.callTrade(t, true); graph.pool.start(keepCalls = true) }
                rule.waitUntil(600_000) { graph.pool.state?.running == false }
                val p2 = graph.pool.state!!
                // a forced piece either moves or is listed as stuck; it never just silently stays
                val moved = p2.builds[t.taker]?.items?.map { it.id }?.containsAll(t.items) == true
                if (!moved && p2.trades.none { it.stuck && it.items.containsAll(t.items) }) add("a trade the player made neither happens nor says why", "pool trades")
                Log.i("ScreenAudit", "overruled trade: moved=$moved")
                p2.trades.firstOrNull { it.made }?.let { m ->
                    ui { graph.pool.callTrade(m, false); graph.pool.start(keepCalls = true) }
                    rule.waitUntil(600_000) { graph.pool.state?.running == false }
                    if (graph.pool.state!!.builds[m.taker]?.items?.any { it.id in m.items } == true) add("a piece the player kept still moves", "pool trades")
                } ?: warn("no made trade to overrule", "pool trades")
                ui { graph.pool.keep = emptyMap(); graph.pool.give = emptyMap() }
            }
            val used = p.builds.values.flatMap { it?.items.orEmpty() }.filter { !it.ghost }.map { it.id }
            if (used.size != used.toSet().size) add("a piece goes to two heroes", "pool")
            ui { undo?.let { graph.heroes.undoEquip(it) }; graph.heroes.config(a).request = ra; graph.heroes.config(b).request = rb; graph.pool.state = poolDone; graph.optimize.search = keepSearch }
        }
        // "keep as is": a kept hero's worn gear must stay off every build of the re-run
        run {
            val wornBy = graph.scan.box!!.items.filter { it.equippedOn != null }.associate { it.id to it.equippedOn!! }
            val kept = poolDone.builds.values.flatMap { it?.items.orEmpty() }.mapNotNull { wornBy[it.id] }.firstOrNull { it !in poolDone.ids }
            if (kept == null) add("can't find a hero whose gear the pool run takes", "keep as is")
            else {
                ui { graph.pool.skip = setOf(kept); graph.pool.start() }
                rule.waitUntil(600_000) { graph.pool.state?.running == false }
                val keptGear = graph.scan.box!!.gearOf(kept).values.map { it.id }.toSet()
                if (graph.pool.state!!.builds.values.any { b -> b?.items?.any { it.id in keptGear } == true }) add("a kept hero's gear still goes to another hero", "keep as is")
                ui { graph.pool.skip = emptySet(); graph.pool.state = poolDone }
            }
        }

        // Sell review: one search with its keep-rolling numbers, and Find gear: one search; every setup reuses them
        ui { graph.sellReview.find() }
        rule.waitUntil(300_000) { !graph.sellReview.busy }
        runCatching { rule.waitUntil(180_000) { !graph.sellReview.curveBusy && graph.sellReview.keepCurve != null } }
        if (graph.sellReview.result?.matches.isNullOrEmpty()) add("Sell review finds nothing on the fixture, so its cards go unchecked", "sell")
        if (graph.sellReview.keepCurve == null) add("the keep-rolling numbers never arrive", "sell")
        val baseSellQuery = graph.sellReview.query
        val sellFirst = graph.sellReview.result?.matches?.firstOrNull()?.item?.id
        ui { graph.sellReview.result?.matches?.drop(1)?.take(3)?.forEach { graph.marks.markReviewed(it.item.id, keep = true) }; graph.messages.toast = null }
        ui { graph.messages.show(graph.findGear.find()) }
        runCatching { rule.waitUntil(120_000) { graph.findGear.result != null } }

        val steps = mutableListOf<Pair<String, () -> Unit>>()
        fun screen(name: String, vararg stack: Screen) = steps.add(name to { ui { overlay = false; graph.nav.sheet = null; graph.nav.stack = stack.toList() } })
        screen("optimize", Screen.Optimize)
        // Improve heroes' sheets take a piece id and need a finished check: their own steps below open them
        SheetKind.entries.filter { !it.name.startsWith("HERO_UPGRADE_") }.forEach { k -> steps.add("sheet-${k.name.lowercase()}" to { ui { graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(k, hero) } }) }
        steps.add("sheet-targets-edit" to { ui { graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.TARGETS, hero) }; clickText("Speed") })
        steps.add("targets-done-closes-stat" to {
            // close first: the same sheet value would keep the previous step's open stat
            ui { graph.nav.sheet = null }
            ui { graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.TARGETS, hero) }
            clickText("Speed"); clickText("Done with", substring = true)
            if (graph.nav.sheet == null) add("button closes the sheet instead of the stat", "targets")
            else if (rule.onAllNodesWithText("Done with", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) add("stat stays open after Done", "targets")
            ui { graph.nav.sheet = null }
        })
        steps.add("sheet-swipe-closes" to {
            ui { graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.BONUS, hero) }
            rule.onAllNodesWithText("Bonus stats", useUnmergedTree = true).onFirst().performTouchInput { swipeDown(centerY, centerY + 1400f, 250) }
            rule.waitForIdle()
            if (graph.nav.sheet != null) add("sheet stays open after a swipe down", "bonus stats")
            ui { graph.nav.sheet = null }
        })
        steps.add("artifact-pick" to {
            ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize) }
            val before = graph.heroes.config(hero).artifact
            clickText("Artifact")
            if (graph.nav.sheet?.kind != SheetKind.ARTIFACT) add("Artifact row does not open the artifact list", "optimize")
            else {
                val role = graph.heroes.hero(hero)?.let { graph.heroes.gameHero(it) }?.role
                val a = graph.content.content.artifacts.sortedBy { if (it.role == role || it.role == null) 0 else 1 }.first { it.code != before }
                clickText(a.name)
                if (graph.heroes.config(hero).artifact != a.code) add("tapping an artifact does not choose it", "artifact")
                if (graph.nav.sheet != null) add("artifact list stays open after a pick", "artifact")
                ui { graph.nav.sheet = Sheet(SheetKind.ARTIFACT, hero, back = SheetKind.BONUS) }
                clickText("Use the preset's artifact")
                if (graph.nav.sheet?.kind != SheetKind.BONUS) add("artifact list opened from Bonus stats does not return there", "artifact")
            }
            ui { graph.heroes.config(hero).artifact = before; graph.nav.sheet = null; graph.rev.bump() }
        })
        steps.add("artifact-5-stars" to {
            ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.ARTIFACT, hero) }
            clickText("5★")
        })
        steps.add("rta-after-cache-only-load" to {
            // the Heroes list loads low tiers from cache only; a later online load must still fetch the RTA samples
            val code = graph.heroes.hero(hero)!!.code
            // offline the samples can't be fetched: keep the cached ones and check the online load still runs
            val online = graph.heroes.presetNetwork
            ui { graph.heroes.presets.remove(code); graph.heroes.presetsOnline -= code
                if (online) graph.heroes.forgetPreset(code) }
            ui { graph.heroes.loadPresetsQuietly(hero, network = false) }
            rule.waitUntil(10_000) { graph.heroes.presets.containsKey(code) && graph.heroes.presetLoading[code] != true }
            ui { graph.heroes.loadPresetsQuietly(hero, network = true) }
            if (online) {
                runCatching { rule.waitUntil(30_000) { graph.heroes.presets[code]?.rta?.isNotEmpty() == true } }
                if (graph.heroes.presets[code]?.rta.isNullOrEmpty()) add("RTA samples never load after a cache-only load", "presets")
            } else {
                runCatching { rule.waitUntil(10_000) { code in graph.heroes.presetsOnline && graph.heroes.presetLoading[code] != true } }
                if (code !in graph.heroes.presetsOnline) add("an online preset load after a cache-only load is skipped", "presets")
            }
        })
        steps.add("sets-torrent-x3" to {
            val torrent = graph.content.content.setByName("Torrent")!!.key
            ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.heroes.request(hero).setAlts = listOf(mapOf(torrent to 6)); graph.rev.bump(); graph.nav.sheet = Sheet(SheetKind.SETS, hero) }
        })
        steps.add("sets-health-crit-plus" to {
            val health = graph.content.content.setByName("Health")!!.key; val crit = graph.content.content.setByName("Critical")!!.key
            ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.heroes.request(hero).setAlts = listOf(mapOf(health to 2, crit to 2)); graph.rev.bump(); graph.nav.sheet = Sheet(SheetKind.SETS, hero) }
        })
        steps.add("edits-survive-restart" to restart@{
            // Android may close the app while the player is in the game; an edit must already be on disk.
            // once: a second graph holds a whole box in memory
            if (setup != setups[0]) return@restart
            val st = e7.gearscan.data.Stat.SPD
            val was = graph.heroes.request(hero).targets[st.ordinal]
            ui { graph.nav.sheet = null; graph.heroes.request(hero).targets[st.ordinal] = 271.0; graph.rev.bump() }
            Thread.sleep(1_500)
            var fresh: AppGraph? = null
            rule.runOnUiThread { fresh = restartedGraph(ctx) }
            rule.waitUntil(20_000) { fresh!!.ready }
            if (fresh!!.heroes.request(hero).targets[st.ordinal] != 271.0) add("a target edit is lost when the app restarts", "state")
            fresh = null
            ui { graph.heroes.request(hero).targets[st.ordinal] = was; graph.rev.bump() }
        })
        steps.add("sets-tap-health-x3" to {
            val health = graph.content.content.setByName("Health")!!.key
            ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.SETS, hero) }
            clickText("Any sets")
            runCatching { clickText("Health") }.onFailure { e -> add("can't tap Health: ${e.message?.take(60)}", "sets") }
            repeat(2) { runCatching { clickDesc("More Health") }.onFailure { e -> add("can't tap Health's +: ${e.message?.take(60)}", "sets") } }
            if (graph.heroes.request(hero).setAlts != listOf(mapOf(health to 6))) add("Health and + twice don't ask for three Health sets", graph.heroes.request(hero).setAlts.toString())
            runCatching { clickText("Health") }
            if (graph.heroes.request(hero).setAlts.isNotEmpty()) add("one tap on Health ×3 doesn't drop it", graph.heroes.request(hero).setAlts.toString())
            ui { graph.heroes.request(hero).setAlts = listOf(mapOf(health to 6)); graph.rev.bump() }
        })
        steps.add("goal-damage-skills" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.heroes.request(hero).goal = e7.gearscan.data.Goal.DAMAGE; graph.rev.bump(); graph.nav.sheet = Sheet(SheetKind.GOALS, hero) } })
        steps.add("pool-setup" to { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize) } })
        steps.add("pool-role" to { ui {
            // a hero with no build at all, as the role sheet expects
            val h = graph.scan.box!!.heroes.first { graph.heroes.gameHero(it) != null && it.id !in poolDone.ids }
            graph.heroes.config(h.id).request = null; graph.heroes.presets[h.code] = null; graph.pool.extra = setOf(h.id)
            graph.nav.stack = listOf(Screen.Optimize, Screen.PoolHero(h.id, setup = true))
        } })
        steps.add("pool-role-picked" to { ui { (graph.nav.screen as? Screen.PoolHero)?.let { graph.heroes.applyRole(it.id, e7.gearscan.data.Role.DAMAGE) } } })
        steps.add("pool-role-sets" to { ui { (graph.nav.screen as? Screen.PoolHero)?.let { graph.nav.sheet = Sheet(SheetKind.SETS, it.id) } } })
        steps.add("pool-ready-hero" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolHero(poolDone.ids.first(), setup = false)) } })
        steps.add("pool-reviewed-resume" to {
            // Next marks a hero reviewed; the pool page then resumes at the first hero not yet reviewed
            // the row says "loading builds" instead while any preset is still loading
            rule.waitUntil(30_000) { graph.pool.heroes().none { graph.heroes.hasBuild(it.id) == null } }
            val ready = graph.pool.heroes().filter { graph.heroes.hasBuild(it.id) == true }.map { it.id }
            ui { graph.nav.sheet = null; ready.forEach { graph.heroes.request(it).reviewed = false }; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolHero(ready.first(), setup = false)) }
            runCatching { clickText("Next", substring = true) }.onFailure { add("can't tap Next: ${it.message?.take(60)}", "pool hero") }
            if (!graph.heroes.request(ready.first()).reviewed) add("Next does not mark the hero reviewed", "pool hero")
            ui { graph.nav.stack = listOf(Screen.Optimize) }
            runCatching { clickText("use their preset", substring = true) }.onFailure {
                add("can't tap the reviewed row: " + rule.onAllNodesWithText("use their preset", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                    .joinToString { n -> n.boundsInRoot.toString() }, "pool")
            }
            if (graph.nav.sheet?.kind != SheetKind.POOL_LIST) add("the reviewed row does not open the hero list", "pool")
            // the sheet slides in; tap once the button is on screen
            rule.waitUntil(5_000) { runCatching { clickText("Review next", substring = true) }.isSuccess }
            val at = (graph.nav.stack.lastOrNull() as? Screen.PoolHero)?.id
            if (ready.size > 1 && at != ready[1]) add("pool walk does not resume at the first hero not reviewed", "pool")
            ui { ready.forEach { graph.heroes.request(it).reviewed = false } }
        })
        // bump(): the same hero screen may already be open, and only a change it reads makes it redraw
        steps.add("pool-hero-reviewed" to { ui { graph.nav.sheet = null; graph.heroes.request(poolDone.ids.first()).reviewed = true; graph.rev.bump(); graph.nav.stack = listOf(Screen.Optimize, Screen.PoolHero(poolDone.ids.first(), setup = false)) } })
        steps.add("pool-hero-unreview" to {
            // the reviewed stamp takes the mark off one hero
            val id = poolDone.ids.first()
            runCatching { clickText("reviewed") }.onFailure { add("can't tap the reviewed stamp: ${it.message?.take(60)}", "pool hero") }
            if (graph.heroes.request(id).reviewed) add("tapping the reviewed stamp leaves the hero reviewed", "pool hero")
        })
        steps.add("pool-list-clear" to {
            // Clear in Heroes in this run takes every mark off
            val ids = graph.pool.members().map { it.id }
            ui { graph.nav.stack = listOf(Screen.Optimize); ids.take(3).forEach { graph.heroes.request(it).reviewed = true }; graph.nav.sheet = Sheet(SheetKind.POOL_LIST, 0) }
            runCatching { clickText("Clear"); clickText("Reset") }.onFailure { add("can't tap Clear: ${it.message?.take(60)}", "pool list") }
            if (ids.any { graph.heroes.request(it).reviewed }) add("Clear leaves heroes reviewed", "pool list")
        })
        steps.add("pool-rules" to { ui { graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.features.pool.POOL_RULES) } })
        steps.add("pool-list" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.POOL_LIST, 0) } })
        steps.add("pool-hero-previous" to {
            val ready = graph.pool.heroes().filter { graph.heroes.hasBuild(it.id) == true }.map { it.id }
            if (ready.size > 1) {
                ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolHero(ready[1], setup = false)) }
                runCatching { clickText("Previous", substring = true) }.onFailure { add("can't tap Previous: ${it.message?.take(60)}", "pool hero") }
                if ((graph.nav.stack.lastOrNull() as? Screen.PoolHero)?.id != ready[0]) add("Previous does not go back one hero", "pool hero")
            }
        })
        steps.add("sheet-reopens-on-back" to {
            // a screen opened from a sheet returns to that sheet
            val first = graph.pool.heroes().firstOrNull()
            if (first != null) {
                ui { graph.nav.tab(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.POOL_LIST, 0) }
                rule.waitUntil(5_000) { runCatching { clickText(graph.heroes.heroName(first)) }.isSuccess }
                if (graph.nav.stack.lastOrNull() !is Screen.PoolHero) add("tapping a hero in the run list opens nothing", "pool list")
                ui { graph.nav.back() }
                if (graph.nav.sheet?.kind != SheetKind.POOL_LIST) add("back from a hero does not reopen Heroes in this run", "pool list")
                ui { graph.nav.sheet = null }
            }
        })
        steps.add("pool-worse-opens-hero" to {
            // the test account's run takes nothing from heroes in it, so give the first hero one piece the second wears
            val p = poolDone
            val a = p.ids.firstOrNull { p.builds[it] != null }
            val b = p.ids.firstOrNull { it != a && p.builds[it] != null && graph.scan.box!!.gearOf(it).isNotEmpty() }
            val piece = b?.let { graph.scan.box!!.gearOf(it).values.firstOrNull() }
            val ba = a?.let { p.builds[it] }
            if (a == null || b == null || piece == null || ba == null) { add("can't set up a taken piece", "pool compare"); return@to }
            val items = ba.items.map { if (it.slot == piece.slot) piece else it }
            ui {
                graph.pool.state = p.copy(builds = p.builds + (a to e7.gearscan.opt.Build(items, ba.value, ba.final, ba.ratings, ba.fromOthers)))
                graph.nav.sheet = null; graph.nav.tab(Screen.Optimize); graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun, Screen.Compare(0, b))
            }
            rule.onAllNodesWithText("Taken by other heroes", useUnmergedTree = true).let { n ->
                runCatching { n[0].performScrollTo() }
                if (n.fetchSemanticsNodes().isEmpty()) add("a piece taken by another hero is not listed", "pool compare")
            }
            // the taker's portrait opens its build, and there the piece's "on <hero>" leads back
            val aName = graph.heroes.hero(a)?.let(graph.heroes::heroName)
            runCatching { clickText("to $aName") }.onFailure { add("can't tap the hero that took a piece: ${it.message?.take(60)}", "pool compare") }
            if (graph.nav.stack.lastOrNull() != Screen.Compare(0, a)) add("tapping the hero that took a piece doesn't open its build", "pool compare")
            else {
                runCatching { clickText("on ${graph.heroes.hero(b)?.let(graph.heroes::heroName)}") }.onFailure { add("can't tap the hero a piece came from: ${it.message?.take(60)}", "pool compare") }
                if (graph.nav.stack.lastOrNull() != Screen.Compare(0, b)) add("tapping the hero a piece came from doesn't open its build", "pool compare")
            }
            // the next step sets the pool state again
        })
        steps.add("pool-running" to { ui {
            graph.nav.sheet = null; graph.pool.extra = emptySet()
            val half = poolDone.ids.take(poolDone.ids.size / 2)
            graph.pool.state = poolDone.copy(running = true, builds = poolDone.builds.filterKeys { it in half }, after = poolDone.after.filterKeys { it in half },
                current = poolDone.ids.getOrNull(half.size), currentBest = 81, startedAt = System.currentTimeMillis() - 168_000)
            graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun)
        } })
        steps.add("pool-done" to { ui { graph.pool.state = poolDone; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun) } })
        steps.add("pool-worse" to { ui { graph.nav.sheet = Sheet(SheetKind.POOL_WORSE, 0) } })
        steps.add("pool-low" to { ui { graph.nav.sheet = Sheet(SheetKind.POOL_LOW, 0) } })
        steps.add("pool-heroes" to { ui { graph.nav.sheet = Sheet(SheetKind.POOL_HEROES, 0) } })
        steps.add("pool-hero-compare" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun, Screen.Compare(0, poolDone.builds.keys.first())) } })
        steps.add("pool-done-trades" to { ui { graph.nav.sheet = null; tradesPool?.let { graph.pool.state = it }; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun) } })
        steps.add("pool-trades" to { ui { graph.nav.sheet = Sheet(SheetKind.POOL_TRADES, 0) } })
        steps.add("pool-trades-changed" to {
            val t = tradesPool?.trades?.firstOrNull()
            if (t == null) add("no contested trade to show", "pool trades")
            else {
                runCatching { clickText(if (graph.pool.tradeMoves(t)) "Stays" else "Moves") }.onFailure { add("can't tap a trade's choice: ${it.message?.take(60)}", "pool trades") }
                if (rule.onAllNodesWithText("Re-run with 1 change", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("changing a trade doesn't offer a re-run", "pool trades")
            }
        })
        steps.add("history" to { ui { graph.nav.sheet = null; graph.pool.state = poolDone; graph.optimize.search = single; graph.nav.tab(Screen.Optimize); graph.history.shown = true } })
        steps.add("history-open-pool" to {
            // an older run opens read only; the pending one opens live, ready to equip
            val e = graph.history.entries.firstOrNull { it.pool && it.at != graph.pool.pending }
            if (e == null) add("no older pool run in history to open", "history")
            else {
                ui { graph.nav.tab(Screen.Optimize); graph.history.shown = true; openHistory(graph, e) }
                if (graph.nav.screen != Screen.PoolRun || graph.pool.state?.fromHistory != true) add("opening a pool run from history doesn't show it", "history")
                if (rule.onAllNodesWithText("Just record", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) add("a run from history offers to equip", "history")
                if (rule.onAllNodesWithText("Checklist", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("a pool run from history has no checklist", "history")
                ui { graph.checklist.loadPool() }
                if (graph.checklist.build?.flag == null) add("a pool run's checklist from history isn't flagged", "history")
            }
            graph.history.entries.firstOrNull { it.at == graph.pool.pending }?.let { p ->
                ui { graph.nav.tab(Screen.Optimize); graph.history.shown = true; openHistory(graph, p) }
                if (graph.pool.state?.fromHistory != false || graph.pool.state?.startedAt != p.at) add("the pending run opens read only from history", "history")
                ui { graph.checklist.loadPool() }
                if (graph.checklist.build?.flag != null) add("the pending run's checklist is flagged as from history", "history")
            } ?: add("no pending run in history", "history")
            ui { graph.nav.tab(Screen.Optimize); graph.history.shown = true; e?.let { openHistory(graph, it) } }
        })
        steps.add("overlay-pool" to {
            ui { graph.history.entries.firstOrNull { it.at == graph.pool.pending }?.let { openHistory(graph, it) }
                graph.checklist.loadPool(); graph.overlay.expanded = true; graph.overlay.tab = 0 }
            if (graph.checklist.build?.pieces?.any { it.forHero != null } != true) add("the pool checklist has no moves to show", "overlay-pool")
            overlay = true
        })
        steps.add("history-open-hero-latest" to {
            overlay = false
            val e = graph.history.entries.firstOrNull { !it.pool }
            if (e == null) add("no one-hero search in history", "history")
            else {
                ui { graph.pool.state = poolDone; graph.optimize.search = null; graph.nav.tab(Screen.Optimize); graph.history.shown = true; openHistory(graph, e) }
                if (graph.nav.screen != Screen.Results || graph.optimize.search?.startedAt != e.at || graph.optimize.search?.historyAt != 0L) add("the newest search doesn't open live from history", "history")
                ui { graph.nav.go(Screen.Compare(0)) }
                for (t in listOf("Show over game", "Equip in app"))
                    if (rule.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("the newest search from history has no \"$t\"", "history")
            }
        })
        steps.add("history-open-hero" to {
            val e = graph.history.entries.filter { !it.pool }.getOrNull(1)
            if (e == null) add("no older one-hero search in history", "history")
            else {
                ui { graph.nav.tab(Screen.Optimize); graph.history.shown = true; openHistory(graph, e) }
                if (graph.nav.screen != Screen.Results || graph.optimize.search?.historyAt != e.at) add("opening a search from history doesn't show it", "history")
            }
        })
        steps.add("history-hero-compare" to {
            if (graph.optimize.search?.historyAt?.let { it != 0L } != true || graph.optimize.search?.builds.isNullOrEmpty()) add("no search from history with a build to open", "history")
            else {
                ui { graph.nav.go(Screen.Compare(0)) }
                if (rule.onAllNodesWithText("Show over game", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("a build from history has no checklist", "history")
                if (rule.onAllNodesWithText("Equip in app", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) add("a build from history offers to equip", "history")
            }
        })
        steps.add("pool-review" to {
            // the walk opens at the first change, Next marks it reviewed and moves on
            ui { graph.optimize.search = single; graph.pool.state = poolDone.copy(reviewed = emptySet()); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun) }
            val queue = graph.pool.reviewQueue()
            if (queue.isEmpty()) add("the pool run has no changes to review", "pool review")
            else {
                runCatching { clickText("Every hero") }.onFailure { add("can't tap Every hero: ${it.message?.take(60)}", "pool review") }
                if (graph.nav.sheet?.kind != SheetKind.POOL_HEROES) add("Every hero doesn't open the hero list", "pool review")
                runCatching { clickText("Review next · ", substring = true) }.onFailure { add("can't tap Review next: ${it.message?.take(60)}", "pool review") }
                if (graph.nav.screen != Screen.PoolReview(queue[0])) add("Review next doesn't open the first change", "pool review")
                else {
                    // a long hero name shortens the button to "Next ›"
                    runCatching { clickText(if (queue.size > 1) "Next" else "Done", substring = true) }.onFailure { add("can't tap Next: ${it.message?.take(60)}", "pool review") }
                    if (queue[0] !in graph.pool.state!!.reviewed) add("Next doesn't mark the hero reviewed", "pool review")
                    if (queue.size > 1 && graph.nav.screen != Screen.PoolReview(queue[1])) add("Next doesn't move to the next change", "pool review")
                }
            }
        })
        steps.add("pool-pending" to {
            // the last run waits on the Whole pool page until it's equipped; equipping clears it, undo brings it back
            ui { graph.pool.state = poolDone; graph.pool.pending = poolDone.startedAt; graph.nav.sheet = null; graph.history.shown = false; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize) }
            if (rule.onAllNodesWithText("Pending run", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("no pending run on the Whole pool page", "pool pending")
        })
        steps.add("pool-pending-equip" to {
            ui { graph.pool.equip() }
            if (graph.pool.pending != null) add("equipping doesn't make the pending run done", "pool pending")
            ui { graph.messages.toast?.onAction?.invoke(); graph.messages.toast = null }
            if (graph.pool.pending != poolDone.startedAt) add("undoing the equip doesn't bring the pending run back", "pool pending")
        })
        steps.add("pool-setup-edited" to {
            // editing a hero's targets while reviewing offers a re-run from Pool done
            val id = poolDone.builds.keys.first { poolDone.builds[it] != null }
            val keep = graph.heroes.request(id).copy()
            ui {
                graph.pool.state = poolDone; graph.pool.mode = false; graph.nav.stack = listOf(Screen.Optimize, Screen.PoolRun)
                graph.heroes.request(id).targets.indices.firstOrNull { !graph.heroes.request(id).targets[it].isNaN() }?.let { graph.heroes.request(id).targets[it] += 10 }; graph.rev.bump()
            }
            if (rule.onAllNodesWithText("Setup changed for", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("an edited target doesn't offer a re-run", "pool review")
            ui { graph.heroes.config(id).request = keep; graph.rev.bump() }
        })
        steps.add("pool-review-reset" to { ui { graph.history.shown = false; graph.pool.state = poolDone; graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize) } })
        steps.add("pool-trades-reset" to { ui { graph.pool.keep = emptyMap(); graph.pool.give = emptyMap(); graph.nav.sheet = null } })
        steps.add("app-update" to {
            ui { graph.nav.sheet = null; graph.pool.mode = false; graph.nav.stack = listOf(Screen.Optimize)
                graph.appUpdate.release = e7.gearscan.data.AppUpdate.Release("9.9.9", "https://example.invalid", "https://example.invalid/E7-Gear-9.9.9.apk", 1) }
            // the installed APK is this app with this key, so only its version may stop it
            val own = e7.gearscan.AppInstall.refusal(ctx, java.io.File(ctx.applicationInfo.sourceDir))
            if (own != "it isn't newer than this version") add("the update check misreads an APK", "app update: $own")
            val agent = graph.http.open("https://example.invalid").getRequestProperty("User-Agent")
            if (agent != "E7Gear/${graph.appUpdate.version}") add("requests don't name the app and version", "user agent: $agent")
        })
        steps.add("app-update-failed" to {
            ui { graph.appUpdate.update() }
            rule.waitUntil(30_000) { graph.appUpdate.progress == null && graph.appUpdate.problem != null }
            if (graph.appUpdate.problem?.startsWith("Download stopped") != true) add("a failed update download isn't shown", "app update: ${graph.appUpdate.problem}")
        })
        steps.add("pool-off" to { ui { graph.appUpdate.release = null; graph.nav.sheet = null; graph.pool.state = poolDone; graph.pool.mode = false; graph.nav.stack = listOf(Screen.Optimize) } })
        screen("heroes", Screen.Heroes)
        steps.add("heroes-tier-editor" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Heroes) }; clickText(graph.heroes.heroName(graph.heroes.hero(hero)!!)) })
        steps.add("heroes-unranked" to { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Heroes) }; clickText("Tiers 2 and 1", substring = true) })
        screen("hero-detail", Screen.Heroes, Screen.HeroDetail(hero))
        screen("scan-intro", Screen.ScanIntro)
        // first start: Scan & data must be reachable without agreeing to scan, and both ways in leave Optimize under it
        steps.add("first-start-restore-link" to {
            ui { graph.nav.sheet = null; graph.settings.consent = false; graph.nav.stack = listOf(Screen.ScanIntro) }
            clickText("Restore a backup or import a gear.txt instead"); rule.waitForIdle()
            if (graph.nav.stack != listOf(Screen.Optimize, Screen.ScanData)) add("first start traps you on Scan & data", "restore link: ${graph.nav.stack}")
        })
        steps.add("first-start-continue" to {
            ui { graph.nav.sheet = null; graph.settings.consent = false; graph.nav.stack = listOf(Screen.ScanIntro) }
            clickText("I understand what is sent and agree to scan"); clickText("Continue"); rule.waitForIdle()
            if (graph.nav.stack != listOf(Screen.Optimize, Screen.ScanData)) add("first start traps you on Scan & data", "continue: ${graph.nav.stack}")
            if (!graph.settings.consent) add("first start loses the scan consent", "continue")
        })
        steps.add("app-update-page-only" to {
            ui { graph.nav.sheet = null; graph.pool.mode = false; graph.appUpdate.release = e7.gearscan.data.AppUpdate.Release("9.9.9", "https://github.com/Sod3n/e7-gear/releases"); graph.nav.stack = listOf(Screen.Optimize) }
            if (rule.onAllNodesWithText("E7 Gear 9.9.9 is out", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("a newer release shows no card", "app update")
        })
        steps.add("app-update-reset" to { ui { graph.appUpdate.release = null } })
        screen("scan-data", Screen.Optimize, Screen.ScanData)
        screen("capture-ready", Screen.ScanData, Screen.Capture)
        steps.add("capture-running" to { capture(graph, 20_000) })
        steps.add("capture-lobby" to { capture(graph, 300_000) })
        ScanError.entries.forEach { e -> steps.add("capture-error-${e.name.lowercase()}" to { ui { resetCapture(graph); graph.scan.error = e } }) }
        // import a box missing a few items first, so the result has real "new items" to show
        val older = org.json.JSONObject(gear).apply { val a = getJSONArray("items"); repeat(5) { a.remove(0) } }.toString()
        steps.add("scan-result" to { ui { resetCapture(graph); graph.scan.import(older); graph.scan.import(gear); graph.nav.stack = listOf(Screen.Optimize, Screen.ScanData, Screen.ScanResult) } })
        (0..2).forEach { screen("diff-$it", Screen.ScanResult, Screen.DiffItems(it)) }
        // the Gear tab and its three modes
        screen("gear-home", Screen.Gear)
        steps.add("sell-setup" to { ui { graph.nav.sheet = null; graph.messages.dialog = null; graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters) } })
        steps.add("sell-matches" to { clickText("Matches") })
        steps.add("sell-matches-flat" to { ui { graph.sellReview.grouped = true }; clickText("Worst rolls"); if (graph.sellReview.grouped) add("Sell review's Worst rolls switch doesn't turn grouping off", "sell") })
        steps.add("sell-matches-grouped" to { ui { graph.sellReview.grouped = false }; clickText("By set"); if (!graph.sellReview.grouped) add("Sell review's By set switch doesn't turn grouping on", "sell") })
        listOf(SheetKind.SELL_WORTH, SheetKind.SELL_SAFEGUARDS, SheetKind.SELL_INVENTORY, SheetKind.SELL_SLOTS, SheetKind.SELL_SETS, SheetKind.SELL_MAINS, SheetKind.SELL_EXTRA).forEach { k ->
            steps.add("sell-${k.name.lowercase()}" to { ui { graph.messages.dialog = null; graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters); graph.nav.sheet = Sheet(k, 0) } })
        }
        steps.add("sell-worth-per-slot" to { ui { graph.messages.dialog = null; graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters); graph.nav.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; clickText("Per slot", substring = true) })
        steps.add("sell-details" to { ui { graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters) }; clickText("Matches"); ui { graph.nav.sheet = sellFirst?.let { Sheet(SheetKind.SELL_DETAILS, it) } } })
        steps.add("sell-explain" to { ui { graph.nav.sheet = Sheet(SheetKind.SELL_WORTH, 0) }; clickText("Total gold") })
        steps.add("sell-confirm" to { ui { graph.messages.dialog = null; graph.nav.sheet = Sheet(SheetKind.SELL_WORTH, 0); graph.messages.askReset("Reset the ES bars?", "Every slot goes back to the middle ES of your own +15 pieces.") {} } })
        steps.add("sell-kept" to { ui { graph.messages.dialog = null; graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters, Screen.SellKept) } })
        steps.add("sell-kept-grouped" to { ui { graph.sellReview.keptGrouped = false }; clickText("By set"); if (!graph.sellReview.keptGrouped) add("Kept pieces' By set switch doesn't group the pieces", "sell kept") })
        steps.add("sell-kept-back" to {
            ui { graph.nav.back() }
            if (graph.nav.screen != Screen.SellFilters || rule.onAllNodesWithText("Worth rolling").fetchSemanticsNodes().isEmpty()) add("back from Kept pieces doesn't return to the Sell review tab you left", "sell kept: ${graph.nav.screen}")
        })
        steps.add("sell-overlay" to {
            ui { graph.nav.sheet = null; graph.trackMatches(openOverlay = false); graph.tracker.show(); graph.overlay.expanded = true }
            overlay = true
        })
        // the collapsed tracker: slot, level, ring, chance badge and the piece's set
        steps.add("sell-bubble" to { ui { graph.overlay.expanded = false } })
        steps.add("improve-heroes" to { ui { overlay = false; graph.tracker.hide(); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades()) } })
        // the states a player decides in need a finished check (~3 min on the emulator), run once when first asked for
        fun heroUpgradesDone(): e7.gearscan.opt.HeroUpgrades.Result {
            if (graph.improveHeroes.heroUpgrades?.result == null || graph.improveHeroes.heroUpgrades?.running == true) {
                ui { graph.improveHeroes.start() }
                rule.waitUntil(20 * 60_000) { graph.improveHeroes.heroUpgrades?.running == false && graph.improveHeroes.heroUpgrades?.result != null }
            }
            return graph.improveHeroes.heroUpgrades!!.result!!
        }
        // the audit's own fixed setups can leave every hero with nothing to gain; HeroUpgradeScreens covers real setups
        fun upgradeHero() = graph.improveHeroes.view(heroUpgradesDone()).firstOrNull { it.rows.isNotEmpty() }
            ?: run { warn("Improve heroes found no hero with gains on the audit's setups, so its hero page and piece sheet go unchecked", "improve-heroes"); null }
        steps.add("improve-heroes-by-hero" to { heroUpgradesDone(); ui { overlay = false; graph.tracker.hide(); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades()) } })
        steps.add("improve-heroes-by-piece" to { heroUpgradesDone(); ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades()) }; clickText("By piece") })
        steps.add("improve-heroes-by-piece-grouped" to { ui { graph.improveHeroes.grouped = false }; clickText("By set"); if (!graph.improveHeroes.grouped) add("Improve heroes' By set tab doesn't group the pieces", "improve-heroes") })
        steps.add("improve-heroes-settings" to { heroUpgradesDone(); ui { graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades()); graph.nav.sheet = Sheet(SheetKind.HERO_UPGRADE_SETTINGS, 0) } })
        steps.add("improve-heroes-hero" to { val h = upgradeHero() ?: return@to; ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades(), Screen.HeroUpgrades(h.hero.id)) } })
        steps.add("improve-heroes-piece" to { val h = upgradeHero() ?: return@to
            ui { graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades(), Screen.HeroUpgrades(h.hero.id)); graph.nav.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, h.rows.first().pick.piece) } })
        steps.add("improve-heroes-help" to { ui { graph.nav.stack = listOf(Screen.Gear, Screen.HeroUpgrades()); graph.nav.sheet = Sheet(SheetKind.HERO_UPGRADE_HELP, 0) } })
        screen("find-gear", Screen.Gear, Screen.GearPotential)
        val findGear = graph.findGear
        val findGearQuery = findGear.query
        fun findGearSearch(q: PotentialQuery): Boolean {
            ui { overlay = false; graph.nav.sheet = null; findGear.edit(q); graph.messages.show(findGear.find()) }
            return runCatching { rule.waitUntil(120_000) { !findGear.busy && findGear.result != null && !findGear.stale } }.isSuccess
        }
        steps.add("find-gear-setup" to {
            ui { overlay = false; graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential) }
            runCatching { clickText("Set up") }.onFailure {
                val roots = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().size
                val at = rule.onAllNodesWithText("Set up", useUnmergedTree = true).fetchSemanticsNodes().map { n -> n.boundsInRoot }
                add("can't open Find gear's Set up tab", "$roots roots, Set up at $at, overlay ${graph.overlay.running}: ${it.message?.take(60)}")
            }
        })
        steps.add("find-gear-piece" to {
            if (!findGearSearch(findGearQuery)) add("the Find gear search never finishes on the fixture", "find-gear")
            val first = findGear.result?.matches?.firstOrNull()
            if (first == null) add("Find gear finds nothing on the fixture, so its results and piece sheet go unchecked", "find-gear")
            else ui { graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential); graph.nav.sheet = Sheet(SheetKind.POTENTIAL_PIECE, first.item.id) }
        })
        steps.add("find-gear-stale" to {
            ui { graph.nav.sheet = null; findGear.edit(findGearQuery.copy(slots = setOf(Slot.HELMET, Slot.NECKLACE))); graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential) }
            if (findGear.result == null) add("no Find gear result to go stale", "find-gear")
            else if (!findGear.stale) add("changing a Find gear filter does not mark the list stale", "find-gear")
        })
        steps.add("find-gear-none" to {
            if (!findGearSearch(findGearQuery.copy(targets = listOf(e7.gearscan.data.GearStat.SPD, e7.gearscan.data.GearStat.CR).associateWith { e7.gearscan.opt.GearBounds.target(it, findGearQuery.reforge, findGearQuery.show == 2).maximum.toString() }, rareOnly = false))) add("the empty Find gear search never finishes", "find-gear")
            else if (findGear.result?.matches?.isNotEmpty() != false) add("a search no piece can meet (Speed and Crit chance both at their maximum) still finds pieces", "find-gear")
            ui { graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential) }
        })
        steps.add("find-gear-restored" to {
            if (!findGearSearch(findGearQuery)) add("the Find gear search cannot be restored", "find-gear")
            ui { graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential) }
        })
        steps.add("find-gear-grouped" to { ui { findGear.grouped = false }; clickText("By set"); if (!findGear.grouped) add("Find gear's By set switch doesn't group the matches", "find-gear") })
        // a walk that isn't a Sell review keeps the owner's top row too: undo beside collapse
        steps.add("find-gear-overlay" to {
            ui { graph.nav.sheet = null; graph.messages.show(graph.trackAllMatches(openOverlay = false)); graph.tracker.show(); graph.overlay.expanded = true }
            overlay = true
            rule.waitForIdle()
            if (rule.onAllNodesWithContentDescription("Undo last step").fetchSemanticsNodes().isEmpty()) add("a Find gear walk over the game has no undo", "find-gear-overlay")
        })
        steps.add("scan-restart-sheet" to { ui { overlay = false; graph.tracker.hide(); resetCapture(graph); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.ScanData); graph.nav.sheet = Sheet(SheetKind.SCAN_RESTART, 0) } })
        steps.add("scan-restart-scan-now" to {
            // "not running" hands the scan to the screen under the sheet; with no game installed it lands on that error
            runCatching { clickText("It's not running · scan now") }.onFailure { add("can't tap scan now: ${it.message?.take(60)}", "scan restart") }
            rule.waitForIdle()
            if (graph.nav.screen != Screen.Capture) add("scan now from the restart sheet doesn't start the scan", "scan restart")
            ui { resetCapture(graph) }
        })
        steps.add("backup-saved" to {
            // a backup is saved to Downloads and confirmed; sharing is offered, never forced
            ui { resetCapture(graph); graph.nav.sheet = null; graph.messages.toast = null; graph.nav.stack = listOf(Screen.Optimize, Screen.ScanData) }
            runCatching { clickText("Back up everything") }.onFailure { add("can't tap Back up everything: ${it.message?.take(60)}", "backup") }
            if (graph.messages.toast?.text?.startsWith("Saved e7gear-backup.json") != true) add("a backup doesn't confirm where it was saved", "backup")
            ui { graph.messages.toast = null }
        })
        screen("feedback", Screen.ScanData, Screen.Feedback())
        screen("feedback-error", Screen.Capture, Screen.Feedback("Nothing captured: Stopped before the lobby"))
        steps.add("results" to { ui { graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize, Screen.Results) } })
        steps.add("ghost" to {
            if (graph.optimize.search?.ghost?.ghost == null) warn("the search found no ghost build, so the Ghost build screen goes unchecked", "ghost")
            else ui { overlay = false; graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Optimize, Screen.Results, Screen.GhostBuild) }
        })
        steps.add("results-none" to {
            // rules no build can meet: the ghost search and the blocker check must both finish and say so
            val req = graph.heroes.request(hero)
            val was = req.mins[Stat.SPD.ordinal]; val ghostWas = req.ghost
            ui { req.mins[Stat.SPD.ordinal] = 999.0; req.ghost = true; graph.nav.stack = listOf(Screen.Optimize, Screen.Results) }
            search(graph, listOf(hero))
            rule.waitForIdle()
            val s = graph.optimize.search!!
            if (s.builds.isNotEmpty()) add("the no-build fixture found a build", "results-none")
            if (!s.ghostSearched) add("no build: the ghost search never ran", "results-none")
            if (s.blockers?.any { it.startsWith("Speed at least 999") } != true) add("no build: the blocking minimum isn't named", "results-none: ${s.blockers}")
            if (rule.onAllNodesWithText("Speed at least 999", substring = true).fetchSemanticsNodes().isEmpty())
                add("no build: the blocking minimum isn't shown", "results-none")
            ui { req.mins[Stat.SPD.ordinal] = was; req.ghost = ghostWas }
        })
        steps.add("results-none-timeout" to {
            ui {
                graph.optimize.search = SearchState(hero, false, current = single!!.current, timedOut = true, ghostSearched = true,
                    blockers = listOf("Effectiveness at least 70", "Crit chance cap", "Speed at least 305"))
                graph.nav.stack = listOf(Screen.Optimize, Screen.Results)
            }
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Search for 60 s").fetchSemanticsNodes().isEmpty()) add("a timed-out empty search offers no longer search", "results-none-timeout")
            if (rule.onAllNodesWithText("No build meets these rules.").fetchSemanticsNodes().isNotEmpty()) add("a timed-out search claims no build exists", "results-none-timeout")
        })
        steps.add("results-timeout" to {
            ui {
                graph.optimize.search = SearchState(hero, false, builds = single!!.builds, current = single.current, timedOut = true, limitMs = 15_000L)
                graph.nav.stack = listOf(Screen.Optimize, Screen.Results)
            }
            rule.waitForIdle()
            if (rule.onAllNodesWithText("Search for 60 s").fetchSemanticsNodes().isEmpty()) add("a timed-out search with builds offers no longer search", "results-timeout")
        })
        steps.add("results-none-done" to { ui { graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize, Screen.Results) } })
        // a build with modded pieces, made from the real #1 so the mod display is checked without a slow mods search
        steps.add("results-mods" to {
            ui {
                val setup = graph.heroes.setup(hero)!!
                val b = single!!.builds.first()
                val items = b.items.map { it -> e7.gearscan.opt.Mods.variants(it, setup.modWanted(), { true }, 6, true, 0.5, true).firstOrNull() ?: it }
                graph.optimize.search = SearchState(hero, false, builds = listOf(setup.build(items)) + single.builds.drop(1), current = single.current)
                graph.nav.stack = listOf(Screen.Optimize, Screen.Results)
            }
        })
        screen("compare-mods", Screen.Optimize, Screen.Results, Screen.Compare(0))
        steps.add("overlay-mods" to {
            ui { graph.optimize.search?.builds?.firstOrNull()?.let { graph.checklist.load(graph.checklist.overlayBuild(hero, it, "Build #1")) {}; graph.overlay.expanded = true; graph.overlay.tab = 0 } }
            overlay = true
            rule.waitForIdle()
            // a modification happens in the game, so it is a step even on a piece the hero already wears
            val modded = graph.checklist.build!!.pieces.withIndex().filter { it.value.mod != null }
            if (modded.isEmpty()) add("the mods fixture has no modified piece", "overlay-mods")
            if (modded.any { graph.checklist.done[it.index] }) add("a modification starts ticked", "overlay-mods")
            // the game modifies in another menu than it equips: every mod waits until all the equipping is done
            val lastEquip = graph.checklist.build!!.pieces.indexOfLast { !it.modStep }
            if (modded.any { it.index < lastEquip }) add("a modification comes before the equipping is done", "overlay-mods")
            // closing the checklist (or the app) and opening the same one again keeps its ticks
            var kept = false
            ui {
                val before = graph.checklist.done.count { it }
                graph.checklist.markCurrentDone()
                graph.optimize.search?.builds?.firstOrNull()?.let { graph.checklist.load(graph.checklist.overlayBuild(hero, it, "Build #1")) {} }
                kept = graph.checklist.done.count { it } == before + 1 && graph.checklist.lastDone != null
                graph.checklist.undo(); graph.overlay.expanded = true; graph.overlay.tab = 0
            }
            if (!kept) add("reopening a checklist loses its ticks", "overlay-mods")
            val shown = rule.onAllNodesWithText("改 Mod:", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
            if (shown < modded.size) add("the checklist doesn't list every modification", "overlay-mods: $shown of ${modded.size}")
        })
        steps.add("sheet-advanced-mods" to {
            overlay = false
            ui { graph.heroes.request(hero).mods = true; graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.ADVANCED, hero) }
        })
        steps.add("mods-off" to { ui { graph.heroes.request(hero).mods = false; graph.nav.sheet = null } })
        screen("compare", Screen.Optimize, Screen.Results, Screen.Compare(0))
        screen("overlay-intro", Screen.Optimize, Screen.Results, Screen.Compare(0), Screen.OverlayIntro)
        steps.add("overlay-bubble" to {
            ui { graph.optimize.search?.builds?.firstOrNull()?.let { graph.checklist.load(graph.checklist.overlayBuild(hero, it, "Build #1")) {} } }
            overlay = true
        })
        steps.add("overlay-bubble-dragging" to { ui { graph.overlay.dragging = true } })
        steps.add("overlay-bubble-held" to { ui { graph.overlay.captured = true } })
        steps.add("overlay-pieces" to { ui { graph.overlay.dragging = false; graph.overlay.captured = false; graph.overlay.expanded = true; graph.overlay.tab = 0 } })
        steps.add("overlay-list-hidden" to { ui { graph.overlay.opacity = 0f } })
        steps.add("overlay-list-shown" to { ui { graph.overlay.opacity = 0.8f } })
        steps.add("overlay-done" to { ui { graph.checklist.done.indices.take(3).forEach { graph.checklist.done[it] = true } } })
        steps.add("overlay-stats" to { ui { graph.overlay.tab = 1 } })
        steps.add("overlay-all-done" to { ui { graph.overlay.tab = 0; graph.checklist.done.indices.forEach { graph.checklist.done[it] = true } } })
        steps.add("overlay-recorded" to { ui { graph.checklist.recorded = true } })
        steps.add("overlay-bubble-all-done" to { ui { graph.checklist.recorded = false; graph.overlay.expanded = false } })
        steps.add("overlay-history-flag" to { ui { graph.optimize.search?.builds?.firstOrNull()?.let {
            graph.checklist.load(graph.checklist.overlayBuild(hero, it, "Build #1", graph.history.flag(System.currentTimeMillis() - 86_400_000))) {} }
            graph.overlay.expanded = true; graph.overlay.tab = 0 } })
        // last, because tracking changes the tracker's piece for every later step
        steps.add("find-gear-rare" to {
            if (findGear.result?.query != findGearQuery && !findGearSearch(findGearQuery)) add("the Find gear search cannot be restored", "find-gear")
            ui { overlay = false; graph.tracker.hide(); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential) }
            if (findGear.result?.rareOnly.isNullOrEmpty()) warn("Find gear's search finds no rare-roll piece on the fixture, so the rare fold goes unchecked", "find-gear")
            else runCatching { clickText("need a rare roll", substring = true) }.onFailure { add("can't open Find gear's rare-roll fold", it.message?.take(60) ?: "") }
                .onSuccess {
                    // the fold says why these pieces have no percent, for the player who opens it
                    runCatching { rule.onAllNodesWithText("A rare roll is", substring = true, useUnmergedTree = true).onFirst().performScrollTo(); rule.waitForIdle() }
                        .onFailure { add("Find gear's rare-roll fold doesn't say why its pieces have no percent", "find-gear") }
                }
        })
        steps.add("track-upgrades" to {
            val m = findGear.result?.withOdds?.firstOrNull { it.item.enhance < 15 }
            if (m == null) { add("no Find gear match below +15 to track, so Track upgrades goes unchecked", "track-upgrades"); return@to }
            ui { overlay = false; graph.tracker.hide(); graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Gear, Screen.GearPotential); graph.messages.show(graph.trackMatch(m)) }
            if (graph.nav.screen != Screen.Upgrade) add("tracking a Find gear match does not open Track upgrades", "track-upgrades")
            else if (graph.nav.backLabel() != "Find gear") add("back from Track upgrades names the wrong screen", graph.nav.backLabel())
        })
        steps.add("track-upgrades-again" to {
            val m = findGear.result?.withOdds?.firstOrNull() ?: return@to
            ui { graph.messages.show(graph.trackMatch(m)) }
            if (graph.nav.backLabel() == "Track upgrades") add("Track upgrades stacked on itself: back names the screen it is on", "track-upgrades")
        })
        steps.add("track-upgrades-choose" to { ui { graph.nav.sheet = null }; runCatching { clickText("Other piece") }.onFailure { add("can't open Track upgrades' piece chooser", it.message?.take(60) ?: "") } })
        // states the other steps don't reach, for the link audit; the box goes last and comes back after the setup's shots
        steps.add("optimize-running" to { ui { overlay = false; graph.nav.sheet = null; graph.pool.mode = false; graph.optimize.search = SearchState(hero, true, current = single!!.current); graph.nav.stack = listOf(Screen.Optimize, Screen.Results) } })
        // the same screens scrolled to a control below the first screenful, found by text that is the same before and after
        fun scrollTo(text: String) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[0].performScrollTo(); rule.waitForIdle() }
        steps.add("scroll-optimize-goal" to { ui { graph.pool.mode = false; graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize) }; scrollTo("Goal") })
        steps.add("scroll-pool-choose" to { ui { graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize) }; scrollTo("one by one") })
        steps.add("scroll-heroes-low" to { ui { graph.pool.mode = false; graph.nav.stack = listOf(Screen.Heroes) }; scrollTo("Tiers 2 and 1") })
        steps.add("scroll-hero-details" to { ui { graph.nav.stack = listOf(Screen.Heroes) }; clickText(graph.heroes.heroName(graph.heroes.hero(hero)!!)); scrollTo("Hero details") })
        steps.add("scroll-scan-intro" to { ui { graph.nav.stack = listOf(Screen.ScanIntro) }; scrollTo("Restore a backup or import a gear.txt instead") })
        steps.add("optimize-stale" to { ui { graph.optimize.search = single; graph.scan.lastScan = System.currentTimeMillis() - 3 * 24 * 3_600_000L; graph.nav.stack = listOf(Screen.Optimize) } })
        steps.add("crash-card" to {
            File(ctx.filesDir, e7.gearscan.features.feedback.CRASH_FILE).writeText("java.lang.IllegalStateException: sample crash for the audit\n\tat e7.gearscan.Sample.run")
            kotlinx.coroutines.runBlocking { graph.feedback.loadCrash() }
            ui { graph.nav.stack = listOf(Screen.Optimize) }
        })
        steps.add("optimize-no-box" to { ui { graph.feedback.dismissCrash(); graph.optimize.search = null; boxAside = graph.scan.box; graph.scan.replaceBox(null); graph.nav.stack = listOf(Screen.Optimize) } })

        // state flips that must not move anything else: (name, open the screen, change the state)
        val sheetOn = { k: SheetKind -> ui { overlay = false; graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(k, hero) } }
        val flips = mutableListOf<Triple<String, () -> Unit, () -> Unit>>(
            Triple("sets: pick a 4-piece", { sheetOn(SheetKind.SETS); ui { graph.heroes.request(hero).setAlts = emptyList() } }, { clickText("Speed") }),
            Triple("sets: add a second 4-piece", { sheetOn(SheetKind.SETS) }, { clickText("Revenge") }),
            Triple("sets: pick a pair", { sheetOn(SheetKind.SETS) }, { clickText("Critical") }),
            Triple("preset: select another build", { sheetOn(SheetKind.PRESET) }, {
                rule.onAllNodesWithText("#2", substring = true, useUnmergedTree = true)[0].performScrollTo().performClick(); rule.waitForIdle()
            }),
            Triple("advanced: mods on", { sheetOn(SheetKind.ADVANCED); ui { graph.heroes.request(hero).mods = false }
                rule.onAllNodesWithText("Allow substat mods", useUnmergedTree = true)[0].performScrollTo(); rule.waitForIdle() }, { ui { graph.heroes.request(hero).mods = true; graph.rev.bump() } }),
            Triple("advanced: crit cap off", { sheetOn(SheetKind.ADVANCED) }, { ui { graph.heroes.request(hero).capCrit = !graph.heroes.request(hero).capCrit; graph.rev.bump() } }),
            Triple("goal: pick another", { sheetOn(SheetKind.GOALS) }, {
                ui { graph.heroes.request(hero).let { r -> r.goal = if (r.goal == e7.gearscan.data.Goal.DAMAGE) e7.gearscan.data.Goal.EHP else e7.gearscan.data.Goal.DAMAGE }; graph.rev.bump() }
            }),
            Triple("targets: change weight", { sheetOn(SheetKind.TARGETS); clickText("Speed") }, { ui { graph.heroes.request(hero).weights.let { w -> w[Stat.SPD.ordinal] = if (w[Stat.SPD.ordinal] == 3) 1 else 3 }; graph.rev.bump() } }),
            Triple("heroes: builds arrive", {
                ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Heroes); graph.scan.box!!.heroes.forEach { graph.heroes.presets.remove(it.code) } }
            }, { ui { graph.scan.box!!.heroes.forEach { h -> graph.heroes.loadPresetsQuietly(h.id, network = false) } }; Thread.sleep(1500) }),
            Triple("results: match ↔ damage", { ui { graph.nav.sheet = null; graph.heroes.request(hero).goal = e7.gearscan.data.Goal.TARGETS; graph.optimize.search = single; graph.nav.stack = listOf(Screen.Optimize, Screen.Results) } },
                { ui { graph.optimize.showMatch = !graph.optimize.showMatch } }),
            Triple("advanced: change cap", { sheetOn(SheetKind.ADVANCED) }, { ui { graph.heroes.request(hero).maxChanges = if (graph.heroes.request(hero).maxChanges == 2) 6 else 2; graph.rev.bump() } }),
            Triple("pool: tick a tier", { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize) } },
                { ui { graph.pool.tiers = if (4 in graph.pool.tiers) graph.pool.tiers - 4 else graph.pool.tiers + 4 } }),
            Triple("pool: a target edit moves the score", { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize) } }, {
                ui {
                    // a hero short on a target gets targets at its current stats (score up); one already there, after an
                    // earlier setup did that, gets them 10% higher (score down): either way the score has to move
                    val h = graph.pool.heroes().first { h -> graph.heroes.currentBuild(h.id) != null &&
                        e7.gearscan.data.Stat.entries.any { st -> !graph.heroes.request(h.id).targets[st.ordinal].isNaN() } }
                    val r = graph.heroes.request(h.id); val cur = graph.heroes.currentBuild(h.id)!!
                    val short = e7.gearscan.data.Stat.entries.any { st -> !r.targets[st.ordinal].isNaN() && cur.final[st] < r.targets[st.ordinal] - 1 }
                    for (st in e7.gearscan.data.Stat.entries) if (!r.targets[st.ordinal].isNaN())
                        r.targets[st.ordinal] = if (short) Math.floor(cur.final[st]) else Math.floor(cur.final[st] * 1.1 + 5)
                    graph.rev.bump()
                }
            }),
            Triple("pool: effort", { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.features.pool.POOL_RULES) } }, {
                ui { graph.pool.effort = if (graph.pool.effort == e7.gearscan.opt.PoolEffort.TIDY) e7.gearscan.opt.PoolEffort.BALANCED else e7.gearscan.opt.PoolEffort.TIDY }
            }),
            Triple("pool: losses", { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize); graph.nav.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.features.pool.POOL_RULES) } }, {
                ui { graph.pool.losses = if (graph.pool.losses == e7.gearscan.opt.PoolLosses.SAFE) e7.gearscan.opt.PoolLosses.SMALL else e7.gearscan.opt.PoolLosses.SAFE }
            }),
            Triple("pool: clear reviewed", { ui { graph.nav.sheet = null; graph.pool.mode = true; graph.nav.stack = listOf(Screen.Optimize); graph.pool.members().take(3).forEach { graph.heroes.request(it.id).reviewed = true }
                graph.rev.bump(); graph.nav.sheet = Sheet(SheetKind.POOL_LIST, 0) } }, { clickText("Clear"); clickText("Reset") }),
            Triple("hero: change tier", { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.Heroes, Screen.HeroDetail(hero)) } }, { ui { graph.heroes.setTier(hero, 1) } }),
            Triple("capture: the box arrives", { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.ScanData, Screen.Capture) }; capture(graph, 20_000) },
                { capture(graph, 300_000) }),
            Triple("feedback: topic", { ui { graph.nav.sheet = null; graph.nav.stack = listOf(Screen.ScanData, Screen.Feedback()) } }, { clickText("Idea") }),
            Triple("feedback: tick", { ui { graph.nav.stack = listOf(Screen.ScanData, Screen.Feedback()) } }, { clickText("Include the last scan log") }),
            Triple("overlay: piece done", { ui { graph.optimize.search = single; single!!.builds.firstOrNull()?.let { graph.checklist.load(graph.checklist.overlayBuild(hero, it, "Build #1")) {} }; graph.overlay.expanded = true; graph.overlay.tab = 0 }; overlay = true },
                // tick a piece that isn't the open one: the open (current) piece moving on is the checklist's own flow
                { ui { graph.checklist.done.indices.lastOrNull { !graph.checklist.done[it] && it != graph.checklist.current }?.let { graph.checklist.done[it] = true } } }),
        )

        flips += listOf(
            Triple("sell: a safeguard to off", { ui { overlay = false; graph.nav.stack = listOf(Screen.Gear, Screen.SellFilters); graph.nav.sheet = Sheet(SheetKind.SELL_SAFEGUARDS, 0) }
                runCatching { rule.onAllNodesWithText("Spd", useUnmergedTree = true)[0].performScrollTo() }; rule.waitForIdle() },
                { ui { graph.sellReview.edit(graph.sellReview.query.copy(keepStats = graph.sellReview.query.keepStats - e7.gearscan.data.GearStat.SPD)) } }),
            Triple("sell: keep-rolling chance", { ui { graph.sellReview.edit(graph.sellReview.query.copy(keepStats = e7.gearscan.data.SellQuery.DEFAULT_GUARDS)); graph.nav.sheet = Sheet(SheetKind.SELL_WORTH, 0) } },
                { ui { graph.sellReview.edit(graph.sellReview.query.copy(rollOn = if (graph.sellReview.query.rollOn == 1.0) 10.0 else 1.0)) } }),
        )
        // flips edit the hero's request; every setup starts from the request the searches were run with
        val baseRequest = graph.heroes.request(hero).copy()
        // a design round checks only what it touched: -e only improve-heroes*,sell-* and -e setups 353-big-en,353-ru
        val args = InstrumentationRegistry.getArguments()
        fun matches(list: String?, name: String) = list == null || list.split(',').map { it.trim() }.any { p -> if (p.endsWith("*")) name.startsWith(p.dropLast(1)) else name == p }
        val only = args.getString("only"); val setupsArg = args.getString("setups")
        for (s in setups.filter { matches(setupsArg, it.name) }) {
            ui { graph.heroes.config(hero).request = baseRequest.copy() }
            Locale.setDefault(s.locale)
            setup = s
            overlay = false
            ui { graph.overlay.expanded = false; resetCapture(graph); graph.heroes.team = listOf(hero); graph.heroes.pickHero(hero) }
            // the last setup's flips edited the sell filters, which drops the keep-rolling numbers
            if (graph.sellReview.query != baseSellQuery || graph.sellReview.keepCurve == null) {
                ui { graph.sellReview.edit(baseSellQuery); graph.sellReview.find() }
                rule.waitUntil(300_000) { !graph.sellReview.busy }
                runCatching { rule.waitUntil(180_000) { !graph.sellReview.curveBusy && graph.sellReview.keepCurve != null } }
            }
            for ((name, act) in steps) {
                if (!matches(only, name)) continue
                try {
                    act(); rule.waitForIdle()
                    overlayShown = overlay
                    check(name, s)
                } catch (e: Throwable) {
                    add("crash", "$name@${s.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
                }
            }
            for ((name, open, flip) in flips) {
                if (!matches(only, name)) continue
                try {
                    open(); rule.waitForIdle()
                    val before = textBounds(); val shotBefore = screenHash()
                    flip(); rule.waitForIdle()
                    val after = textBounds()
                    // which rows are on screen depends on the setup, so loading builds may show nothing new in one of them
                    if (screenHash() == shotBefore && !name.startsWith("heroes: builds arrive")) add("flip changed nothing", "$name@${s.name} (the check proved nothing)")
                    // short texts ("2", "×") are usually the content being changed, not neighbours
                    for ((key, rects) in before.filterKeys { it.length >= 3 }) {
                        val now = after[key] ?: continue
                        if (now.size != rects.size) continue
                        rects.zip(now).forEach { (a, b) ->
                            val dy = b.top - a.top; val dx = b.left - a.left
                            if (Math.abs(dy) > 2 || Math.abs(dx) > 2) add("layout shift", "$name@${s.name}: \"${key.take(40)}\" moved ${dx.toInt()},${dy.toInt()} px")
                        }
                    }
                } catch (e: Throwable) {
                    add("crash", "$name@${s.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
                }
            }
            boxAside?.let { b -> ui { graph.scan.replaceBox(b) }; boxAside = null }
            // findings so far survive a crash in a later setup
            File(shots, "report-partial.txt").writeText("after ${s.name}\n" + found.entries.joinToString("\n") { (k, v) -> "$k\n" + v.joinToString("\n") { "    $it" } })
            ui { overlay = false; graph.nav.sheet = null; graph.heroes.request(hero).capCrit = true; graph.heroes.request(hero).mods = false; graph.heroes.request(hero).goal = e7.gearscan.data.Goal.TARGETS; graph.heroes.setTier(hero, 5); graph.pool.mode = false; graph.history.shown = false; graph.pool.tiers = setOf(5); graph.pool.losses = e7.gearscan.opt.PoolLosses.SAFE; graph.pool.effort = e7.gearscan.opt.PoolEffort.BALANCED; graph.optimize.showMatch = true; graph.heroes.request(hero).maxChanges = 6 }
        }
        Locale.setDefault(Locale.US)

        // gear moved over the game is invisible to the app: finishing a checklist must leave the app's box as the game now is
        run {
            fun worn(id: Long) = graph.scan.box!!.gearOf(id).values.map { it.id }.toSet()
            fun wants(b: e7.gearscan.opt.Build) = b.items.filter { !it.ghost }.map { it.id }.toSet()
            fun finish() {
                ui { graph.checklist.done.indices.forEach { graph.checklist.done[it] = true }; graph.overlay.expanded = true; graph.overlay.tab = 0 }
                overlay = true; rule.waitForIdle()
                runCatching { clickText("Finished · record it") }.onFailure { add("a finished checklist has no record step", "overlay footer") }
                rule.waitForIdle(); overlay = false; rule.waitForIdle()
            }
            val b = single!!.builds.first()
            ui { graph.optimize.search = single; graph.checklist.loadBuild(hero, b, "Build #1") }
            finish()
            if (worn(hero) != wants(b)) add("a finished checklist isn't recorded", "one hero")
            ui { graph.pool.state = poolDone; graph.pool.pending = poolDone.startedAt; graph.checklist.loadPool() }
            finish()
            val wrong = poolDone.builds.filter { (id, pb) -> pb != null && worn(id) != wants(pb) }.keys
            if (wrong.isNotEmpty() || graph.pool.pending != null) add("a finished pool checklist isn't recorded", "${wrong.size} heroes differ, pending ${graph.pool.pending}")
        }

        // last, since a restore reloads every setup the steps above hold: a backup carries history and a restore brings it
        // back (moving to a new signing key depends on it)
        // ~650 screens in one process leave little heap: a second JSON tree of the backup ran out of memory and lost the report
        try {
            val runs = graph.history.entries.size
            val text = graph.scan.backupText()
            if (historyLength(text) != runs) add("a backup doesn't carry the history", "backup")
            var ok = false
            ui { ok = graph.scan.restore(text) }
            if (!ok || graph.history.entries.size != runs || runs == 0) add("restoring a backup loses the history", "backup: $runs runs before, ${graph.history.entries.size} after, ${text.length / 1024} KB, heap ${heapMb()}")
        } catch (e: OutOfMemoryError) {
            add("the backup step ran out of memory", "heap ${heapMb()}")
        }

        fun fmt(m: Map<String, Set<String>>) = m.entries.joinToString("\n") { (k, v) -> "$k\n" + v.joinToString("\n") { "    $it" } }
        val report = fmt(found)
        File(shots, "report.txt").writeText(report.ifEmpty { "clean" } + if (warnings.isEmpty()) "" else "\n\nwarnings (not failing)\n" + fmt(warnings))
        Log.i("ScreenAudit", report.ifEmpty { "clean" })
        assertTrue("screen audit found problems (see audit/report.txt):\n$report", found.isEmpty())
    }

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun heapMb(): String = Runtime.getRuntime().let { "${(it.totalMemory() - it.freeMemory()) shr 20}/${it.maxMemory() shr 20} MB" }

    // history travels as the text of history.json under "files" (backup format 2)
    private fun historyLength(text: String): Int? = android.util.JsonReader(java.io.StringReader(text)).use { r ->
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "files") { r.skipValue(); continue }
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "history.json") { r.skipValue(); continue }
                return org.json.JSONArray(r.nextString()).length()
            }
            return null
        }
        null
    }

    private fun clickText(text: String, substring: Boolean = false, index: Int = 0) {
        val node = rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)[index]
        // a tap off screen fails to inject; scroll it into view first where a scroll parent exists
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun clickDesc(desc: String) {
        val node = rule.onAllNodesWithContentDescription(desc, useUnmergedTree = true)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun screenHash(): Int {
        val b = rule.onRoot().captureToImage().asAndroidBitmap()
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.contentHashCode()
    }

    /** Where each visible text sits, by text (repeated texts in order). */
    private fun textBounds(): Map<String, List<androidx.compose.ui.geometry.Rect>> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true).fetchSemanticsNodes(false)
            .groupBy({ text(it) }, { it.boundsInRoot }).filterKeys { it.isNotBlank() }

    private fun capture(graph: AppGraph, bytes: Int) = ui {
        graph.scan.capture.clear(); graph.scan.capture.open().write(CaptureSession.DIR_S2C, ByteArray(bytes), bytes)
        graph.scan.captureStarted = Long.MAX_VALUE / 2
        graph.scan.conns = 1; graph.scan.bytes = bytes.toLong()
        graph.scan.error = null
        graph.scan.phase = ScanPhase.CAPTURING
        graph.nav.stack = listOf(Screen.ScanData, Screen.Capture)
    }

    private fun resetCapture(graph: AppGraph) {
        graph.scan.capture.clear(); graph.scan.phase = ScanPhase.IDLE; graph.scan.error = null; graph.scan.captureStarted = 0
        graph.nav.stack = listOf(Screen.ScanData, Screen.Capture)
    }

    private fun search(graph: AppGraph, heroes: List<Long>) {
        ui { graph.heroes.team = heroes; graph.heroes.pickHero(heroes[0]); if (heroes.size > 1) graph.heroes.team = heroes; graph.optimize.startSearch() }
        rule.waitUntil(90_000) { graph.optimize.search?.let { !it.running && !it.ghostRunning && !it.checkingBlockers } == true }
    }

    private val GHOST_VERDICTS = listOf("ghost piece beats", "ghost piece can't", "makes a build possible", "Best with 1 ghost")
    private val PROGRESS = listOf("runs after the main search", "Looking for", "Checking which rule to loosen")

    private var overlayShown = false
    private lateinit var graph: AppGraph

    private fun check(step: String, s: Setup) {
        val where = "$step@${s.name}"
        // a progress line with nothing left to run reads as a search that never ends
        val idle = graph.optimize.search?.let { !it.running && !it.ghostRunning && !it.checkingBlockers } ?: true
        if (idle) PROGRESS.filter { rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
            .forEach { add("waits on nothing", "$where: \"$it\"") }
        if (graph.optimize.search?.ghostSearched == false) GHOST_VERDICTS.filter { rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
            .forEach { add("a ghost result nobody searched for", "$where: \"$it\"") }
        val widthPx = s.widthDp * ctx.resources.displayMetrics.density
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), true).fetchSemanticsNodes(false)
        for (n in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (r in layouts) textProblems(r).forEach { add(it, "$where: \"${r.layoutInput.text.text.take(60)}\"") }
            // FitText shrinks to 70% before it cuts; a cut one (a button label, a number) has lost its meaning
            if (n.config.getOrNull(SemanticsProperties.TestTag) == e7.gearscan.ui.FIT_TAG && layouts.any { it.lineCount > 0 && it.isLineEllipsized(0) })
                add("cut to fit", "$where: \"${text(n).take(60)}\"")
            if (n.positionInRoot.x + n.size.width > widthPx + 2 && n.size.width > 0)
                add("past the right edge", "$where: \"${text(n).take(60)}\"")
        }
        // layouts from the kit: a grid row is one height, and rows of one list keep the same trailing slots
        // (an arrow, stamp or chip missing in one row must leave its place empty, not move the rest)
        val cells = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(e7.gearscan.ui.CELL_TAG), true).fetchSemanticsNodes(false)
        // per grid: a page under an open sheet can have its own grid at the same height
        for ((_, row) in cells.groupBy { it.parent?.id to Math.round(it.positionInRoot.y / 4f) }) {
            val hs = row.map { it.size.height }
            if (hs.maxOrNull()!! - hs.minOrNull()!! > 2) add("grid cells in one row differ in height", "$where: ${hs.joinToString()} px")
        }
        val rows = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(e7.gearscan.ui.ROW_TAG), true).fetchSemanticsNodes(false)
        // a row's own slots only: a screen under an open sheet has rows and arrows at the same place on screen
        fun slotsOf(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = n.children.flatMap { c ->
            (if (c.config.getOrNull(SemanticsProperties.TestTag) == e7.gearscan.ui.SLOT_TAG) listOf(c) else emptyList()) + slotsOf(c)
        }
        for ((_, list) in rows.groupBy { Math.round(it.positionInRoot.x) to it.size.width }) {
            val ends = list.map { r -> slotsOf(r).map { Math.round(it.positionInRoot.x - r.positionInRoot.x) to it.size.width }.sortedBy { it.first } }.distinct()
            if (ends.size > 1) add("rows of one list end differently", "$where: ${ends.take(3)}")
        }
        // a remote image missing on the game's CDN is worth knowing but is not the app's bug
        // a screen reached from another one needs a visible way back, not only the system gesture
        if (graph.nav.stack.size > 1 && graph.nav.sheet == null && !overlayShown && textBounds().keys.none { it.startsWith("‹") })
            add("no back link", "${graph.nav.screen::class.simpleName} (first at $where)")
        // a screen opens from several places (a hero from another hero): its back link names where back really goes
        val wantBack = "‹ " + graph.nav.backLabel()
        rule.onAllNodesWithTag(e7.gearscan.ui.BACK_TAG).fetchSemanticsNodes(false).map(::text).filter { it != wantBack }
            .forEach { add("back link names the wrong screen", "\"$it\" goes to \"$wantBack\" (first at $where)") }
        // a back link on the only screen does nothing when tapped
        if (graph.nav.stack.size == 1 && graph.nav.sheet == null && !overlayShown && graph.nav.screen != Screen.Optimize && textBounds().keys.any { it.startsWith("‹ ") })
            add("back link goes nowhere", "${graph.nav.screen::class.simpleName} (first at $where)")
        Problems.drain().forEach { if (it.startsWith("image failed: http")) warn("remote image missing", it) else add("data gap", "$it (first at $where)") }
        runCatching {
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            val w = minOf(bmp.width, widthPx.toInt())
            File(shots, "${s.name}_$step.png").outputStream().use { Bitmap.createBitmap(bmp, 0, 0, w, bmp.height).compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    private var heroNames: Set<String> = emptySet()

    private fun textProblems(r: TextLayoutResult): List<String> {
        val t = r.layoutInput.text.text
        val out = mutableListOf<String>()
        // names are how people find a hero; one cut to "Unbound Knight A…" is a bug, not a style choice
        if (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1) && heroNames.any { t.startsWith(it) }) out += "hero name cut"
        for (i in 0 until r.lineCount - 1) {
            val end = r.getLineEnd(i)
            if (end in 1 until t.length && t[end - 1].isLetterOrDigit() && t[end].isLetterOrDigit()) { out += "broken inside a word"; break }
        }
        // font metrics leave sub-pixel "overflow" on every line; only count what actually disappears
        val inkRight = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) } ?: 0f
        val lost = inkRight - r.size.width > 2 || r.multiParagraph.height - r.size.height > 2 ||
            (r.multiParagraph.didExceedMaxLines && r.layoutInput.maxLines != Int.MAX_VALUE)
        if (lost && r.layoutInput.overflow != TextOverflow.Ellipsis) out += "clipped without …"
        return out
    }

    private fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun add(kind: String, detail: String) { found.getOrPut(kind) { linkedSetOf() } += detail }

    private fun warn(kind: String, detail: String) { warnings.getOrPut(kind) { linkedSetOf() } += detail }
}

private fun openHistory(graph: AppGraph, e: HistoryEntry) = if (e.pool) graph.pool.openFromHistory(e) else graph.optimize.openFromHistory(e)
