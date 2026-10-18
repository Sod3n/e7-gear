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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import e7.gearscan.data.Problems
import e7.gearscan.data.Stat
import e7.gearscan.overlay.OverlayState
import e7.gearscan.overlay.OverlayUi
import e7.gearscan.ui.App
import e7.gearscan.ui.installImageLoader
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
        Setup("360-ru", 360, 1f, Locale("ru", "RU")),
        Setup("411-en", 411, 1f, Locale.US),
        Setup("360-big-en", 360, 1.3f, Locale.US),
        Setup("411-big-ru", 411, 1.3f, Locale("ru", "RU")),
    )

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val found = linkedMapOf<String, MutableSet<String>>()
    private val warnings = linkedMapOf<String, MutableSet<String>>()
    private val shots = File(ctx.getExternalFilesDir(null), "audit")

    @Test
    fun everyScreen() {
        assumeTrue("replaces the app's box; emulator only", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val gear = InstrumentationRegistry.getInstrumentation().context.assets.open("gear.txt").bufferedReader().use { it.readText() }
        shots.deleteRecursively(); shots.mkdirs()
        // the audit clears app data first, which takes back the notification permission a player grants on first launch
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        installImageLoader(ctx)

        var setup by mutableStateOf(setups[0])
        var overlay by mutableStateOf(false)
        rule.runOnUiThread { model = AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        // the APK carries no game data: the first start downloads it from the content site
        rule.waitUntil(60_000) { model.content.heroes.isNotEmpty() }
        rule.runOnUiThread { model.importBox(gear); model.consent = true }
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, setup.fontScale)) {
                Box(Modifier.width(setup.widthDp.dp).fillMaxHeight()) {
                    if (overlay) OverlayUi({ _, _ -> }, {}, {}) else App(model) {}
                }
            }
        }
        heroNames = model.box!!.heroes.map { model.heroName(it) }.filter { it.length > 3 }.toSet()
        val hero = model.box!!.heroes.first { model.gameHero(it) != null && model.box!!.gearOf(it.id).size == 6 }.id
        val team = model.box!!.heroes.filter { model.gameHero(it) != null && model.box!!.gearOf(it.id).size == 6 }.take(3).map { it.id }
        ui {
            model.tiers = model.box!!.heroes.take(40).withIndex().associate { (i, h) -> h.id to 5 - i % 5 } + team.withIndex().associate { (i, id) -> id to 5 - i }
            model.pickHero(hero)
        }
        // presets come from the hero library over the network; the search must run with the one that gets applied
        rule.waitUntil(60_000) { model.request(hero).targets.any { !it.isNaN() } }
        rule.waitForIdle()
        // searches are slow on an emulator; run each once and show the same result in every setup
        search(model, listOf(hero)); val single = model.search
        ui { model.team = listOf(hero); model.pickHero(hero) }
        // match % must read in the same order as the ranking, or "#3 72% above #4 75%" looks like a bug
        if (model.request(hero).goal == e7.gearscan.data.Goal.TARGETS) {
            val pcts = single!!.builds.mapNotNull { model.matchOf(hero, it)?.pct }
            if (pcts.zipWithNext().any { (a, b) -> b > a }) add("results out of match order", pcts.take(10).joinToString())
        }

        // a real pool run over the tier-5 heroes, once; screens below reuse its state
        ui { model.poolTiers = setOf(5); model.poolExtra = emptySet(); model.poolHeroes().forEach { model.loadPresetsQuietly(it.id, network = true) } }
        rule.waitUntil(60_000) { model.poolHeroes().none { model.hasBuild(it.id) == null } }
        val poolT0 = System.currentTimeMillis()
        ui { model.startPool() }
        // the run keeps going in the game: a foreground service shows it, and leaves when it ends
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        fun runNotice() = nm.activeNotifications.any { it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "Optimizing pool" }
        runCatching { rule.waitUntil(5_000) { runNotice() || model.pool?.running == false } }
        if (model.pool?.running == true && !runNotice()) add("no notification while the pool runs", "pool service")
        rule.waitUntil(600_000) { model.pool?.running == false }
        runCatching { rule.waitUntil(3_000) { !runNotice() } }
        if (runNotice()) add("the run notification stays after the run ends", "pool service")
        val poolDone = model.pool!!
        Log.i("ScreenAudit", "pool: ${poolDone.ids.size} heroes in ${(System.currentTimeMillis() - poolT0) / 1000} s, ${poolDone.moves} moves, " +
            "score ${model.poolScore(poolDone.before)} -> ${model.poolScore(poolDone.ids.associateWith { poolDone.after[it] ?: poolDone.before[it] })}")
        if (poolDone.builds.isEmpty()) add("pool", "the pool run produced no builds")
        if (model.pendingPool != poolDone.startedAt) add("a finished pool run isn't pending", "pool pending")
        // history: the run and the earlier one-hero search are recorded, and stored builds come back as they were
        run {
            val e = model.history.firstOrNull { it.pool && it.at == poolDone.startedAt }
            if (e == null) add("a finished pool run is not in history", "history")
            else for ((id, b) in poolDone.builds) {
                if (b == null) continue
                val back = e.builds[id]?.firstOrNull()?.let { model.restoreBuild(id, it) }
                if (back?.items?.map { it.id to it.mod?.to } != b.items.filter { !it.ghost }.map { it.id to it.mod?.to }) add("a stored pool build comes back different", "history: ${model.hero(id)?.let(model::heroName)}")
            }
            if (model.history.none { !it.pool }) add("a finished one-hero search is not in history", "history")
        }
        // Never worse: A wants speed from B, who wears its fastest build; B keeps what it can't spare and ends no slower
        var tradesPool: e7.gearscan.PoolState? = null
        run {
            // B wears the run's fastest boots (or is its fastest hero, when none wears speed boots) and A chases speed
            fun bootSpeed(id: Long) = model.box!!.gearOf(id)[e7.gearscan.data.Slot.BOOTS]?.takeIf { it.mainType == e7.gearscan.data.GearStat.SPD }?.mainValue ?: 0.0
            val b = poolDone.ids.maxBy { bootSpeed(it) * 1000 + (model.currentBuild(it)?.final?.get(Stat.SPD) ?: 0.0) }
            val a = poolDone.ids.first { it != b }
            Log.i("ScreenAudit", "never worse: B wears speed boots ${bootSpeed(b)}")
            val ra = model.request(a).copy(); val rb = model.request(b).copy()
            ui { model.request(b).goal = e7.gearscan.data.Goal.SPEED; model.request(b).lowerPriorityOnly = false }
            val keepSearch = model.search
            search(model, listOf(b))
            val fastest = model.search?.builds?.firstOrNull()
            val undo = fastest?.let { f -> var prev: Map<Long, Long?> = emptyMap(); ui { prev = model.equip(b, f) }; prev }
            val bNow = model.currentBuild(b)
            ui {
                model.request(a).goal = e7.gearscan.data.Goal.SPEED
                model.request(a).lowerPriorityOnly = false
                model.startPool()
            }
            rule.waitUntil(600_000) { model.pool?.running == false }
            val p = model.pool!!
            tradesPool = p
            val notMade = p.trades.filter { !it.made }
            Log.i("ScreenAudit", "never worse: ${notMade.size} trades not made, moves ${p.moves}, B speed ${bNow?.final?.get(Stat.SPD)} -> ${p.builds[b]?.final?.get(Stat.SPD)}")
            if (notMade.isEmpty()) add("the speed conflict made no trade fail, so Never worse went untested", "pool")
            if ((p.builds[b]?.final?.get(Stat.SPD) ?: bNow!!.final[Stat.SPD]) < bNow!!.final[Stat.SPD] - 1e-3) add("Never worse: the Max speed hero ends slower", "pool")
            for (id in p.ids) if ((p.after[id] ?: 0) < (p.before[id] ?: 0)) add("Never worse: a hero's match drops", "pool: ${model.hero(id)?.let(model::heroName)} ${p.before[id]} → ${p.after[id]}")
            if (notMade.any { t -> p.builds[t.taker]?.items?.any { it.id in t.items } == true }) add("a hero got a piece listed as not traded", "pool")
            // overruling: a trade not made, given by the player, happens on the re-run; a piece kept stays
            notMade.firstOrNull()?.let { t ->
                ui { model.callTrade(t, true); model.startPool(keepCalls = true) }
                rule.waitUntil(600_000) { model.pool?.running == false }
                val p2 = model.pool!!
                // a forced piece either moves or is listed as stuck; it never just silently stays
                val moved = p2.builds[t.taker]?.items?.map { it.id }?.containsAll(t.items) == true
                if (!moved && p2.trades.none { it.stuck && it.items.containsAll(t.items) }) add("a trade the player made neither happens nor says why", "pool trades")
                Log.i("ScreenAudit", "overruled trade: moved=$moved")
                p2.trades.firstOrNull { it.made }?.let { m ->
                    ui { model.callTrade(m, false); model.startPool(keepCalls = true) }
                    rule.waitUntil(600_000) { model.pool?.running == false }
                    if (model.pool!!.builds[m.taker]?.items?.any { it.id in m.items } == true) add("a piece the player kept still moves", "pool trades")
                } ?: warn("no made trade to overrule", "pool trades")
                ui { model.poolKeep = emptyMap(); model.poolGive = emptyMap() }
            }
            val used = p.builds.values.flatMap { it?.items.orEmpty() }.filter { !it.ghost }.map { it.id }
            if (used.size != used.toSet().size) add("a piece goes to two heroes", "pool")
            ui { undo?.let { model.undoEquip(it) }; model.config(a).request = ra; model.config(b).request = rb; model.pool = poolDone; model.search = keepSearch }
        }
        // "keep as is": a kept hero's worn gear must stay off every build of the re-run
        run {
            val wornBy = model.box!!.items.filter { it.equippedOn != null }.associate { it.id to it.equippedOn!! }
            val kept = poolDone.builds.values.flatMap { it?.items.orEmpty() }.mapNotNull { wornBy[it.id] }.firstOrNull { it !in poolDone.ids }
            if (kept == null) add("can't find a hero whose gear the pool run takes", "keep as is")
            else {
                ui { model.poolSkip = setOf(kept); model.startPool() }
                rule.waitUntil(600_000) { model.pool?.running == false }
                val keptGear = model.box!!.gearOf(kept).values.map { it.id }.toSet()
                if (model.pool!!.builds.values.any { b -> b?.items?.any { it.id in keptGear } == true }) add("a kept hero's gear still goes to another hero", "keep as is")
                ui { model.poolSkip = emptySet(); model.pool = poolDone }
            }
        }

        val steps = mutableListOf<Pair<String, () -> Unit>>()
        fun screen(name: String, vararg stack: Screen) = steps.add(name to { ui { model.sheet = null; model.stack = stack.toList() } })
        screen("optimize", Screen.Optimize)
        SheetKind.entries.forEach { k -> steps.add("sheet-${k.name.lowercase()}" to { ui { model.stack = listOf(Screen.Optimize); model.sheet = Sheet(k, hero) } }) }
        steps.add("sheet-targets-edit" to { ui { model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.TARGETS, hero) }; clickText("Speed") })
        steps.add("targets-done-closes-stat" to {
            // close first: the same sheet value would keep the previous step's open stat
            ui { model.sheet = null }
            ui { model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.TARGETS, hero) }
            clickText("Speed"); clickText("Done with", substring = true)
            if (model.sheet == null) add("button closes the sheet instead of the stat", "targets")
            else if (rule.onAllNodesWithText("Done with", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) add("stat stays open after Done", "targets")
            ui { model.sheet = null }
        })
        steps.add("sheet-swipe-closes" to {
            ui { model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.BONUS, hero) }
            rule.onAllNodesWithText("Bonus stats", useUnmergedTree = true).onFirst().performTouchInput { swipeDown(centerY, centerY + 1400f, 250) }
            rule.waitForIdle()
            if (model.sheet != null) add("sheet stays open after a swipe down", "bonus stats")
            ui { model.sheet = null }
        })
        steps.add("artifact-pick" to {
            ui { model.sheet = null; model.stack = listOf(Screen.Optimize) }
            val before = model.config(hero).artifact
            clickText("Artifact")
            if (model.sheet?.kind != SheetKind.ARTIFACT) add("Artifact row does not open the artifact list", "optimize")
            else {
                val role = model.hero(hero)?.let { model.gameHero(it) }?.role
                val a = model.content.artifacts.sortedBy { if (it.role == role || it.role == null) 0 else 1 }.first { it.code != before }
                clickText(a.name)
                if (model.config(hero).artifact != a.code) add("tapping an artifact does not choose it", "artifact")
                if (model.sheet != null) add("artifact list stays open after a pick", "artifact")
                ui { model.sheet = Sheet(SheetKind.ARTIFACT, hero, back = SheetKind.BONUS) }
                clickText("Use the preset's artifact")
                if (model.sheet?.kind != SheetKind.BONUS) add("artifact list opened from Bonus stats does not return there", "artifact")
            }
            ui { model.config(hero).artifact = before; model.sheet = null; model.touch() }
        })
        steps.add("artifact-5-stars" to {
            ui { model.sheet = null; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.ARTIFACT, hero) }
            clickText("5★")
        })
        steps.add("rta-after-cache-only-load" to {
            // the Heroes list loads low tiers from cache only; a later online load must still fetch the RTA samples
            val code = model.hero(hero)!!.code
            ui { model.presets.remove(code); model.presetsOnline -= code; File(model.buildsDir, "rta_$code.json").delete(); File(model.buildsDir, "rta_$code.none").delete() }
            ui { model.loadPresetsQuietly(hero, network = false) }
            rule.waitUntil(10_000) { model.presets.containsKey(code) && model.presetLoading[code] != true }
            ui { model.loadPresetsQuietly(hero, network = true) }
            runCatching { rule.waitUntil(30_000) { model.presets[code]?.rta?.isNotEmpty() == true } }
            if (model.presets[code]?.rta.isNullOrEmpty()) add("RTA samples never load after a cache-only load", "presets")
        })
        steps.add("sets-torrent-x3" to {
            val torrent = model.content.setByName("Torrent")!!.key
            ui { model.sheet = null; model.stack = listOf(Screen.Optimize); model.request(hero).setAlts = listOf(mapOf(torrent to 6)); model.touch(); model.sheet = Sheet(SheetKind.SETS, hero) }
        })
        steps.add("edits-survive-restart" to restart@{
            // Android may close the app while the player is in the game; an edit must already be on disk.
            // once: a second AppModel holds a whole box in memory
            if (setup != setups[0]) return@restart
            val st = e7.gearscan.data.Stat.SPD
            val was = model.request(hero).targets[st.ordinal]
            ui { model.sheet = null; model.request(hero).targets[st.ordinal] = 271.0; model.touch() }
            Thread.sleep(1_500)
            var fresh: AppModel? = null
            rule.runOnUiThread { fresh = AppModel(ctx.applicationContext as Application) }
            rule.waitUntil(20_000) { fresh!!.ready }
            if (fresh!!.request(hero).targets[st.ordinal] != 271.0) add("a target edit is lost when the app restarts", "state")
            fresh = null
            ui { model.request(hero).targets[st.ordinal] = was; model.touch() }
        })
        steps.add("sets-tap-health-x3" to {
            val health = model.content.setByName("Health")!!.key
            ui { model.sheet = null; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.SETS, hero) }
            clickText("Any sets")
            repeat(3) { runCatching { clickText("Health") }.onFailure { e -> add("can't tap Health: ${e.message?.take(60)}", "sets") } }
            if (model.request(hero).setAlts != listOf(mapOf(health to 6))) add("three taps on Health don't ask for three Health sets", model.request(hero).setAlts.toString())
        })
        steps.add("goal-damage-skills" to { ui { model.sheet = null; model.stack = listOf(Screen.Optimize); model.request(hero).goal = e7.gearscan.data.Goal.DAMAGE; model.touch(); model.sheet = Sheet(SheetKind.GOALS, hero) } })
        steps.add("pool-setup" to { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize) } })
        steps.add("pool-role" to { ui {
            // a hero with no build at all, as the role sheet expects
            val h = model.box!!.heroes.first { model.gameHero(it) != null && it.id !in poolDone.ids }
            model.config(h.id).request = null; model.presets[h.code] = null; model.poolExtra = setOf(h.id)
            model.stack = listOf(Screen.Optimize, Screen.PoolHero(h.id, setup = true))
        } })
        steps.add("pool-role-picked" to { ui { (model.screen as? Screen.PoolHero)?.let { model.applyRole(it.id, e7.gearscan.data.Role.DAMAGE) } } })
        steps.add("pool-role-sets" to { ui { (model.screen as? Screen.PoolHero)?.let { model.sheet = Sheet(SheetKind.SETS, it.id) } } })
        steps.add("pool-ready-hero" to { ui { model.sheet = null; model.stack = listOf(Screen.Optimize, Screen.PoolHero(poolDone.ids.first(), setup = false)) } })
        steps.add("pool-reviewed-resume" to {
            // Next marks a hero reviewed; the pool page then resumes at the first hero not yet reviewed
            // the row says "loading builds" instead while any preset is still loading
            rule.waitUntil(30_000) { model.poolHeroes().none { model.hasBuild(it.id) == null } }
            val ready = model.poolHeroes().filter { model.hasBuild(it.id) == true }.map { it.id }
            ui { model.sheet = null; ready.forEach { model.request(it).reviewed = false }; model.stack = listOf(Screen.Optimize, Screen.PoolHero(ready.first(), setup = false)) }
            runCatching { clickText("Next", substring = true) }.onFailure { add("can't tap Next: ${it.message?.take(60)}", "pool hero") }
            if (!model.request(ready.first()).reviewed) add("Next does not mark the hero reviewed", "pool hero")
            ui { model.stack = listOf(Screen.Optimize) }
            runCatching { clickText("use their preset", substring = true) }.onFailure {
                add("can't tap the reviewed row: " + rule.onAllNodesWithText("use their preset", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                    .joinToString { n -> n.boundsInRoot.toString() }, "pool")
            }
            if (model.sheet?.kind != SheetKind.POOL_LIST) add("the reviewed row does not open the hero list", "pool")
            // the sheet slides in; tap once the button is on screen
            rule.waitUntil(5_000) { runCatching { clickText("Review next", substring = true) }.isSuccess }
            val at = (model.stack.lastOrNull() as? Screen.PoolHero)?.id
            if (ready.size > 1 && at != ready[1]) add("pool walk does not resume at the first hero not reviewed", "pool")
            ui { ready.forEach { model.request(it).reviewed = false } }
        })
        // touch(): the same hero screen may already be open, and only a change it reads makes it redraw
        steps.add("pool-hero-reviewed" to { ui { model.sheet = null; model.request(poolDone.ids.first()).reviewed = true; model.touch(); model.stack = listOf(Screen.Optimize, Screen.PoolHero(poolDone.ids.first(), setup = false)) } })
        steps.add("pool-hero-unreview" to {
            // the reviewed stamp takes the mark off one hero
            val id = poolDone.ids.first()
            runCatching { clickText("reviewed") }.onFailure { add("can't tap the reviewed stamp: ${it.message?.take(60)}", "pool hero") }
            if (model.request(id).reviewed) add("tapping the reviewed stamp leaves the hero reviewed", "pool hero")
        })
        steps.add("pool-list-clear" to {
            // Clear in Heroes in this run takes every mark off
            val ids = model.poolMembers().map { it.id }
            ui { model.stack = listOf(Screen.Optimize); ids.take(3).forEach { model.request(it).reviewed = true }; model.sheet = Sheet(SheetKind.POOL_LIST, 0) }
            runCatching { clickText("Clear") }.onFailure { add("can't tap Clear: ${it.message?.take(60)}", "pool list") }
            if (ids.any { model.request(it).reviewed }) add("Clear leaves heroes reviewed", "pool list")
        })
        steps.add("pool-rules" to { ui { model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.POOL_RULES) } })
        steps.add("pool-list" to { ui { model.sheet = null; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.POOL_LIST, 0) } })
        steps.add("pool-hero-previous" to {
            val ready = model.poolHeroes().filter { model.hasBuild(it.id) == true }.map { it.id }
            if (ready.size > 1) {
                ui { model.sheet = null; model.stack = listOf(Screen.Optimize, Screen.PoolHero(ready[1], setup = false)) }
                runCatching { clickText("Previous", substring = true) }.onFailure { add("can't tap Previous: ${it.message?.take(60)}", "pool hero") }
                if ((model.stack.lastOrNull() as? Screen.PoolHero)?.id != ready[0]) add("Previous does not go back one hero", "pool hero")
            }
        })
        steps.add("sheet-reopens-on-back" to {
            // a screen opened from a sheet returns to that sheet
            val first = model.poolHeroes().firstOrNull()
            if (first != null) {
                ui { model.tab(Screen.Optimize); model.sheet = Sheet(SheetKind.POOL_LIST, 0) }
                rule.waitUntil(5_000) { runCatching { clickText(model.heroName(first)) }.isSuccess }
                if (model.stack.lastOrNull() !is Screen.PoolHero) add("tapping a hero in the run list opens nothing", "pool list")
                ui { model.back() }
                if (model.sheet?.kind != SheetKind.POOL_LIST) add("back from a hero does not reopen Heroes in this run", "pool list")
                ui { model.sheet = null }
            }
        })
        steps.add("pool-worse-opens-hero" to {
            // the test account's run takes nothing from heroes in it, so give the first hero one piece the second wears
            val p = poolDone
            val a = p.ids.firstOrNull { p.builds[it] != null }
            val b = p.ids.firstOrNull { it != a && p.builds[it] != null && model.box!!.gearOf(it).isNotEmpty() }
            val piece = b?.let { model.box!!.gearOf(it).values.firstOrNull() }
            val ba = a?.let { p.builds[it] }
            if (a == null || b == null || piece == null || ba == null) { add("can't set up a taken piece", "pool compare"); return@to }
            val items = ba.items.map { if (it.slot == piece.slot) piece else it }
            ui {
                model.pool = p.copy(builds = p.builds + (a to e7.gearscan.opt.Build(items, ba.value, ba.final, ba.ratings, ba.fromOthers)))
                model.sheet = null; model.tab(Screen.Optimize); model.stack = listOf(Screen.Optimize, Screen.PoolRun, Screen.Compare(0, b))
            }
            rule.onAllNodesWithText("Taken by other heroes", useUnmergedTree = true).let { n ->
                runCatching { n[0].performScrollTo() }
                if (n.fetchSemanticsNodes().isEmpty()) add("a piece taken by another hero is not listed", "pool compare")
            }
            // the taker's portrait opens its build, and there the piece's "on <hero>" leads back
            val aName = model.hero(a)?.let(model::heroName)
            runCatching { clickText("to $aName") }.onFailure { add("can't tap the hero that took a piece: ${it.message?.take(60)}", "pool compare") }
            if (model.stack.lastOrNull() != Screen.Compare(0, a)) add("tapping the hero that took a piece doesn't open its build", "pool compare")
            else {
                runCatching { clickText("on ${model.hero(b)?.let(model::heroName)}") }.onFailure { add("can't tap the hero a piece came from: ${it.message?.take(60)}", "pool compare") }
                if (model.stack.lastOrNull() != Screen.Compare(0, b)) add("tapping the hero a piece came from doesn't open its build", "pool compare")
            }
            // the next step sets the pool state again
        })
        steps.add("pool-running" to { ui {
            model.sheet = null; model.poolExtra = emptySet()
            val half = poolDone.ids.take(poolDone.ids.size / 2)
            model.pool = poolDone.copy(running = true, builds = poolDone.builds.filterKeys { it in half }, after = poolDone.after.filterKeys { it in half },
                current = poolDone.ids.getOrNull(half.size), currentBest = 81, startedAt = System.currentTimeMillis() - 168_000)
            model.stack = listOf(Screen.Optimize, Screen.PoolRun)
        } })
        steps.add("pool-done" to { ui { model.pool = poolDone; model.stack = listOf(Screen.Optimize, Screen.PoolRun) } })
        steps.add("pool-worse" to { ui { model.sheet = Sheet(SheetKind.POOL_WORSE, 0) } })
        steps.add("pool-low" to { ui { model.sheet = Sheet(SheetKind.POOL_LOW, 0) } })
        steps.add("pool-heroes" to { ui { model.sheet = Sheet(SheetKind.POOL_HEROES, 0) } })
        steps.add("pool-hero-compare" to { ui { model.sheet = null; model.stack = listOf(Screen.Optimize, Screen.PoolRun, Screen.Compare(0, poolDone.builds.keys.first())) } })
        steps.add("pool-done-trades" to { ui { model.sheet = null; tradesPool?.let { model.pool = it }; model.stack = listOf(Screen.Optimize, Screen.PoolRun) } })
        steps.add("pool-trades" to { ui { model.sheet = Sheet(SheetKind.POOL_TRADES, 0) } })
        steps.add("pool-trades-changed" to {
            val t = tradesPool?.trades?.firstOrNull()
            if (t == null) add("no contested trade to show", "pool trades")
            else {
                runCatching { clickText(if (model.tradeMoves(t)) "Stays" else "Moves") }.onFailure { add("can't tap a trade's choice: ${it.message?.take(60)}", "pool trades") }
                if (rule.onAllNodesWithText("Re-run with 1 change", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("changing a trade doesn't offer a re-run", "pool trades")
            }
        })
        steps.add("history" to { ui { model.sheet = null; model.pool = poolDone; model.search = single; model.tab(Screen.Optimize); model.showHistory = true } })
        steps.add("history-open-pool" to {
            // an older run opens read only; the pending one opens live, ready to equip
            val e = model.history.firstOrNull { it.pool && it.at != model.pendingPool }
            if (e == null) add("no older pool run in history to open", "history")
            else {
                ui { model.tab(Screen.Optimize); model.showHistory = true; model.openHistory(e) }
                if (model.screen != Screen.PoolRun || model.pool?.fromHistory != true) add("opening a pool run from history doesn't show it", "history")
                if (rule.onAllNodesWithText("Equip all", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) add("a run from history offers to equip", "history")
            }
            model.history.firstOrNull { it.at == model.pendingPool }?.let { p ->
                ui { model.tab(Screen.Optimize); model.showHistory = true; model.openHistory(p) }
                if (model.pool?.fromHistory != false || model.pool?.startedAt != p.at) add("the pending run opens read only from history", "history")
            } ?: add("no pending run in history", "history")
            ui { model.tab(Screen.Optimize); model.showHistory = true; e?.let { model.openHistory(it) } }
        })
        steps.add("history-open-hero" to {
            val e = model.history.firstOrNull { !it.pool }
            if (e != null) {
                ui { model.pool = poolDone; model.tab(Screen.Optimize); model.showHistory = true; model.openHistory(e) }
                if (model.screen != Screen.Results || model.search?.historyAt != e.at) add("opening a search from history doesn't show it", "history")
            }
        })
        steps.add("pool-review" to {
            // the walk opens at the first change, Next marks it reviewed and moves on
            ui { model.search = single; model.pool = poolDone.copy(reviewed = emptySet()); model.sheet = null; model.stack = listOf(Screen.Optimize, Screen.PoolRun) }
            val queue = model.reviewQueue()
            if (queue.isEmpty()) add("the pool run has no changes to review", "pool review")
            else {
                runCatching { clickText("Every hero · ", substring = true) }.onFailure { add("can't tap Every hero: ${it.message?.take(60)}", "pool review") }
                if (model.sheet?.kind != SheetKind.POOL_HEROES) add("Every hero doesn't open the hero list", "pool review")
                runCatching { clickText("Review next · ", substring = true) }.onFailure { add("can't tap Review next: ${it.message?.take(60)}", "pool review") }
                if (model.screen != Screen.PoolReview(queue[0])) add("Review next doesn't open the first change", "pool review")
                else {
                    runCatching { clickText(if (queue.size > 1) "Next · " else "Done", substring = true) }.onFailure { add("can't tap Next: ${it.message?.take(60)}", "pool review") }
                    if (queue[0] !in model.pool!!.reviewed) add("Next doesn't mark the hero reviewed", "pool review")
                    if (queue.size > 1 && model.screen != Screen.PoolReview(queue[1])) add("Next doesn't move to the next change", "pool review")
                }
            }
        })
        steps.add("pool-pending" to {
            // the last run waits on the Whole pool page until it's equipped; equipping clears it, undo brings it back
            ui { model.pool = poolDone; model.pendingPool = poolDone.startedAt; model.sheet = null; model.showHistory = false; model.poolMode = true; model.stack = listOf(Screen.Optimize) }
            if (rule.onAllNodesWithText("Pending run", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("no pending run on the Whole pool page", "pool pending")
        })
        steps.add("pool-pending-equip" to {
            ui { model.equipPool() }
            if (model.pendingPool != null) add("equipping doesn't make the pending run done", "pool pending")
            ui { model.toast?.onAction?.invoke(); model.toast = null }
            if (model.pendingPool != poolDone.startedAt) add("undoing the equip doesn't bring the pending run back", "pool pending")
        })
        steps.add("pool-setup-edited" to {
            // editing a hero's targets while reviewing offers a re-run from Pool done
            val id = poolDone.builds.keys.first { poolDone.builds[it] != null }
            val keep = model.request(id).copy()
            ui {
                model.pool = poolDone; model.poolMode = false; model.stack = listOf(Screen.Optimize, Screen.PoolRun)
                model.request(id).targets.indices.firstOrNull { !model.request(id).targets[it].isNaN() }?.let { model.request(id).targets[it] += 10 }; model.touch()
            }
            if (rule.onAllNodesWithText("Setup changed for", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("an edited target doesn't offer a re-run", "pool review")
            ui { model.config(id).request = keep; model.touch() }
        })
        steps.add("pool-review-reset" to { ui { model.showHistory = false; model.pool = poolDone; model.search = single; model.stack = listOf(Screen.Optimize) } })
        steps.add("pool-trades-reset" to { ui { model.poolKeep = emptyMap(); model.poolGive = emptyMap(); model.sheet = null } })
        steps.add("app-update" to {
            ui { model.sheet = null; model.poolMode = false; model.stack = listOf(Screen.Optimize)
                model.appUpdate = e7.gearscan.data.AppUpdate.Release("9.9.9", "https://example.invalid", "https://example.invalid/E7-Gear-9.9.9.apk", 1) }
            // the installed APK is this app with this key, so only its version may stop it
            val own = e7.gearscan.AppInstall.refusal(ctx, java.io.File(ctx.applicationInfo.sourceDir))
            if (own != "it isn't newer than this version") add("the update check misreads an APK", "app update: $own")
            val agent = e7.gearscan.data.Http.open("https://example.invalid").getRequestProperty("User-Agent")
            if (agent != "E7Gear/${model.appVersion}") add("requests don't name the app and version", "user agent: $agent")
        })
        steps.add("app-update-failed" to {
            ui { model.updateApp() }
            rule.waitUntil(30_000) { model.updateProgress == null && model.updateProblem != null }
            if (model.updateProblem?.startsWith("Download stopped") != true) add("a failed update download isn't shown", "app update: ${model.updateProblem}")
        })
        steps.add("pool-off" to { ui { model.appUpdate = null; model.sheet = null; model.pool = poolDone; model.poolMode = false; model.stack = listOf(Screen.Optimize) } })
        screen("heroes", Screen.Heroes)
        steps.add("heroes-tier-editor" to { ui { model.sheet = null; model.stack = listOf(Screen.Heroes) }; clickText(model.heroName(model.hero(hero)!!)) })
        steps.add("heroes-unranked" to { ui { model.sheet = null; model.stack = listOf(Screen.Heroes) }; clickText("Show") })
        screen("hero-detail", Screen.Heroes, Screen.HeroDetail(hero))
        screen("scan-intro", Screen.ScanIntro)
        steps.add("app-update-page-only" to {
            ui { model.sheet = null; model.poolMode = false; model.appUpdate = e7.gearscan.data.AppUpdate.Release("9.9.9", "https://github.com/Sod3n/e7-gear/releases"); model.stack = listOf(Screen.Optimize) }
            if (rule.onAllNodesWithText("E7 Gear 9.9.9 is out", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) add("a newer release shows no card", "app update")
        })
        steps.add("app-update-reset" to { ui { model.appUpdate = null } })
        screen("scan-data", Screen.Optimize, Screen.ScanData)
        screen("capture-ready", Screen.ScanData, Screen.Capture)
        steps.add("capture-running" to { capture(model, 20_000) })
        steps.add("capture-lobby" to { capture(model, 300_000) })
        ScanError.entries.forEach { e -> steps.add("capture-error-${e.name.lowercase()}" to { ui { resetCapture(model); model.scanError = e } }) }
        // import a box missing a few items first, so the result has real "new items" to show
        val older = org.json.JSONObject(gear).apply { val a = getJSONArray("items"); repeat(5) { a.remove(0) } }.toString()
        steps.add("scan-result" to { ui { resetCapture(model); model.importBox(older); model.importBox(gear); model.stack = listOf(Screen.Optimize, Screen.ScanData, Screen.ScanResult) } })
        (0..2).forEach { screen("diff-$it", Screen.ScanResult, Screen.DiffItems(it)) }
        steps.add("scan-restart-sheet" to { ui { resetCapture(model); model.sheet = null; model.stack = listOf(Screen.Optimize, Screen.ScanData); model.sheet = Sheet(SheetKind.SCAN_RESTART, 0) } })
        steps.add("scan-restart-scan-now" to {
            // "not running" hands the scan to the screen under the sheet; with no game installed it lands on that error
            runCatching { clickText("It's not running · scan now") }.onFailure { add("can't tap scan now: ${it.message?.take(60)}", "scan restart") }
            rule.waitForIdle()
            if (model.screen != Screen.Capture) add("scan now from the restart sheet doesn't start the scan", "scan restart")
            ui { resetCapture(model) }
        })
        steps.add("backup-saved" to {
            // a backup is saved to Downloads and confirmed; sharing is offered, never forced
            ui { resetCapture(model); model.sheet = null; model.toast = null; model.stack = listOf(Screen.Optimize, Screen.ScanData) }
            runCatching { clickText("Back up everything") }.onFailure { add("can't tap Back up everything: ${it.message?.take(60)}", "backup") }
            if (model.toast?.text?.startsWith("Saved e7gear-backup.json") != true) add("a backup doesn't confirm where it was saved", "backup")
            ui { model.toast = null }
        })
        screen("feedback", Screen.ScanData, Screen.Feedback())
        screen("feedback-error", Screen.Capture, Screen.Feedback("Nothing captured: Stopped before the lobby"))
        steps.add("results" to { ui { model.search = single; model.stack = listOf(Screen.Optimize, Screen.Results) } })
        screen("ghost", Screen.Optimize, Screen.Results, Screen.GhostBuild)
        // a build with modded pieces, made from the real #1 so the mod display is checked without a slow mods search
        steps.add("results-mods" to {
            ui {
                val setup = model.setup(hero)!!
                val b = single!!.builds.first()
                val items = b.items.map { it -> e7.gearscan.opt.Mods.variants(it, setup.modWanted(), { true }, 6, true, 0.5, true).firstOrNull() ?: it }
                model.search = SearchState(hero, false, builds = listOf(setup.build(items)) + single.builds.drop(1), current = single.current)
                model.stack = listOf(Screen.Optimize, Screen.Results)
            }
        })
        screen("compare-mods", Screen.Optimize, Screen.Results, Screen.Compare(0))
        steps.add("overlay-mods" to {
            ui { model.search?.builds?.firstOrNull()?.let { OverlayState.load(model.overlayBuild(hero, it, "Build #1")); OverlayState.expanded = true; OverlayState.tab = 0 } }
            overlay = true
        })
        steps.add("sheet-advanced-mods" to {
            overlay = false
            ui { model.request(hero).mods = true; model.search = single; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.ADVANCED, hero) }
        })
        steps.add("mods-off" to { ui { model.request(hero).mods = false; model.sheet = null } })
        screen("compare", Screen.Optimize, Screen.Results, Screen.Compare(0))
        screen("overlay-intro", Screen.Optimize, Screen.Results, Screen.Compare(0), Screen.OverlayIntro)
        steps.add("overlay-bubble" to {
            ui { model.search?.builds?.firstOrNull()?.let { OverlayState.load(model.overlayBuild(hero, it, "Build #1")) } }
            overlay = true
        })
        steps.add("overlay-bubble-closing" to { ui { OverlayState.closeZone = true } })
        steps.add("overlay-pieces" to { ui { OverlayState.closeZone = false; OverlayState.expanded = true; OverlayState.tab = 0 } })
        steps.add("overlay-done" to { ui { OverlayState.done.indices.take(3).forEach { OverlayState.done[it] = true } } })
        steps.add("overlay-stats" to { ui { OverlayState.tab = 1 } })

        // state flips that must not move anything else: (name, open the screen, change the state)
        val sheetOn = { k: SheetKind -> ui { overlay = false; model.search = single; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(k, hero) } }
        val flips = listOf<Triple<String, () -> Unit, () -> Unit>>(
            Triple("sets: pick a 4-piece", { sheetOn(SheetKind.SETS); ui { model.request(hero).setAlts = emptyList() } }, { clickText("Speed") }),
            Triple("sets: add a second 4-piece", { sheetOn(SheetKind.SETS) }, { clickText("Revenge") }),
            Triple("sets: pick a pair", { sheetOn(SheetKind.SETS) }, { clickText("Critical") }),
            Triple("preset: select another build", { sheetOn(SheetKind.PRESET) }, {
                rule.onAllNodesWithText("#2", substring = true, useUnmergedTree = true)[0].performScrollTo().performClick(); rule.waitForIdle()
            }),
            Triple("advanced: mods on", { sheetOn(SheetKind.ADVANCED); ui { model.request(hero).mods = false }
                rule.onAllNodesWithText("Allow substat mods", useUnmergedTree = true)[0].performScrollTo(); rule.waitForIdle() }, { ui { model.request(hero).mods = true; model.touch() } }),
            Triple("advanced: crit cap off", { sheetOn(SheetKind.ADVANCED) }, { ui { model.request(hero).capCrit = !model.request(hero).capCrit; model.touch() } }),
            Triple("goal: pick another", { sheetOn(SheetKind.GOALS) }, {
                ui { model.request(hero).let { r -> r.goal = if (r.goal == e7.gearscan.data.Goal.DAMAGE) e7.gearscan.data.Goal.EHP else e7.gearscan.data.Goal.DAMAGE }; model.touch() }
            }),
            Triple("targets: change weight", { sheetOn(SheetKind.TARGETS); clickText("Speed") }, { ui { model.request(hero).weights.let { w -> w[Stat.SPD.ordinal] = if (w[Stat.SPD.ordinal] == 3) 1 else 3 }; model.touch() } }),
            Triple("heroes: builds arrive", {
                ui { model.sheet = null; model.stack = listOf(Screen.Heroes); model.box!!.heroes.forEach { model.presets.remove(it.code) } }
            }, { ui { model.box!!.heroes.forEach { h -> model.loadPresetsQuietly(h.id, network = false) } }; Thread.sleep(1500) }),
            Triple("results: match ↔ damage", { ui { model.sheet = null; model.request(hero).goal = e7.gearscan.data.Goal.TARGETS; model.search = single; model.stack = listOf(Screen.Optimize, Screen.Results) } },
                { ui { model.showMatch = !model.showMatch } }),
            Triple("advanced: change cap", { sheetOn(SheetKind.ADVANCED) }, { ui { model.request(hero).maxChanges = if (model.request(hero).maxChanges == 2) 6 else 2; model.touch() } }),
            Triple("pool: tick a tier", { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize) } },
                { ui { model.poolTiers = if (4 in model.poolTiers) model.poolTiers - 4 else model.poolTiers + 4 } }),
            Triple("pool: a target edit moves the score", { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize) } }, {
                ui {
                    // targets set to what the hero has now take it to 100%, so the score has to move
                    val id = model.poolHeroes().first { (model.matchFor(it.id)?.pct ?: 100) < 100 }.id
                    val r = model.request(id); val cur = model.currentBuild(id)!!
                    for (st in e7.gearscan.data.Stat.entries) if (!r.targets[st.ordinal].isNaN()) r.targets[st.ordinal] = Math.floor(cur.final[st])
                    model.touch()
                }
            }),
            Triple("pool: effort", { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.POOL_RULES) } }, {
                ui { model.poolEffort = if (model.poolEffort == e7.gearscan.opt.PoolEffort.TIDY) e7.gearscan.opt.PoolEffort.BALANCED else e7.gearscan.opt.PoolEffort.TIDY }
            }),
            Triple("pool: losses", { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize); model.sheet = Sheet(SheetKind.ADVANCED, e7.gearscan.POOL_RULES) } }, {
                ui { model.poolLosses = if (model.poolLosses == e7.gearscan.opt.PoolLosses.SAFE) e7.gearscan.opt.PoolLosses.SMALL else e7.gearscan.opt.PoolLosses.SAFE }
            }),
            Triple("pool: clear reviewed", { ui { model.sheet = null; model.poolMode = true; model.stack = listOf(Screen.Optimize); model.poolMembers().take(3).forEach { model.request(it.id).reviewed = true }
                model.touch(); model.sheet = Sheet(SheetKind.POOL_LIST, 0) } }, { clickText("Clear") }),
            Triple("hero: change tier", { ui { model.sheet = null; model.stack = listOf(Screen.Heroes, Screen.HeroDetail(hero)) } }, { ui { model.setTier(hero, 1) } }),
            Triple("feedback: topic", { ui { model.sheet = null; model.stack = listOf(Screen.ScanData, Screen.Feedback()) } }, { clickText("Idea") }),
            Triple("feedback: tick", { ui { model.stack = listOf(Screen.ScanData, Screen.Feedback()) } }, { clickText("Include the last scan log") }),
            Triple("overlay: piece done", { ui { model.search = single; single!!.builds.firstOrNull()?.let { OverlayState.load(model.overlayBuild(hero, it, "Build #1")) }; OverlayState.expanded = true; OverlayState.tab = 0 }; overlay = true },
                // tick a piece that isn't the open one: the open (current) piece moving on is the checklist's own flow
                { ui { OverlayState.done.indices.lastOrNull { !OverlayState.done[it] && it != OverlayState.current }?.let { OverlayState.done[it] = true } } }),
        )

        // flips edit the hero's request; every setup starts from the request the searches were run with
        val baseRequest = model.request(hero).copy()
        for (s in setups) {
            ui { model.config(hero).request = baseRequest.copy() }
            Locale.setDefault(s.locale)
            setup = s
            overlay = false
            ui { OverlayState.expanded = false; resetCapture(model); model.team = listOf(hero); model.pickHero(hero) }
            for ((name, act) in steps) {
                try {
                    act(); rule.waitForIdle()
                    overlayShown = overlay
                    check(name, s)
                } catch (e: Throwable) {
                    add("crash", "$name@${s.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
                }
            }
            for ((name, open, flip) in flips) {
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
            ui { overlay = false; model.sheet = null; model.request(hero).capCrit = true; model.request(hero).mods = false; model.request(hero).goal = e7.gearscan.data.Goal.TARGETS; model.setTier(hero, 5); model.poolMode = false; model.showHistory = false; model.poolTiers = setOf(5); model.poolLosses = e7.gearscan.opt.PoolLosses.SAFE; model.poolEffort = e7.gearscan.opt.PoolEffort.BALANCED; model.showMatch = true; model.request(hero).maxChanges = 6 }
        }
        Locale.setDefault(Locale.US)

        // last, since a restore reloads every setup the steps above hold: a backup carries history and a restore brings it
        // back (moving to a new signing key depends on it)
        run {
            val runs = model.history.size
            val text = model.backupText()
            if (org.json.JSONObject(text).optJSONArray("history")?.length() != runs) add("a backup doesn't carry the history", "backup")
            var ok = false
            ui { ok = model.restore(text) }
            if (!ok || model.history.size != runs || runs == 0) add("restoring a backup loses the history", "backup: $runs runs before, ${model.history.size} after")
        }

        fun fmt(m: Map<String, Set<String>>) = m.entries.joinToString("\n") { (k, v) -> "$k\n" + v.joinToString("\n") { "    $it" } }
        val report = fmt(found)
        File(shots, "report.txt").writeText(report.ifEmpty { "clean" } + if (warnings.isEmpty()) "" else "\n\nwarnings (not failing)\n" + fmt(warnings))
        Log.i("ScreenAudit", report.ifEmpty { "clean" })
        assertTrue("screen audit found problems (see audit/report.txt):\n$report", found.isEmpty())
    }

    private fun ui(f: () -> Unit) { rule.runOnUiThread(f); rule.waitForIdle() }

    private fun clickText(text: String, substring: Boolean = false, index: Int = 0) {
        val node = rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)[index]
        // a tap off screen fails to inject; scroll it into view first where a scroll parent exists
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

    private fun capture(model: AppModel, bytes: Int) = ui {
        Capture.clear(); Capture.open().write(Capture.DIR_S2C, ByteArray(bytes), bytes)
        model.captureStarted = Long.MAX_VALUE / 2
        model.captureConns = 1; model.captureBytes = bytes.toLong()
        model.scanError = null
        model.scanPhase = ScanPhase.CAPTURING
        model.stack = listOf(Screen.ScanData, Screen.Capture)
    }

    private fun resetCapture(model: AppModel) {
        Capture.clear(); model.scanPhase = ScanPhase.IDLE; model.scanError = null; model.captureStarted = 0
        model.stack = listOf(Screen.ScanData, Screen.Capture)
    }

    private fun search(model: AppModel, heroes: List<Long>) {
        ui { model.team = heroes; model.pickHero(heroes[0]); if (heroes.size > 1) model.team = heroes; model.startSearch() }
        rule.waitUntil(90_000) { model.search?.let { !it.running && !it.ghostRunning } == true }
    }

    private var overlayShown = false
    private lateinit var model: AppModel

    private fun check(step: String, s: Setup) {
        val where = "$step@${s.name}"
        val widthPx = s.widthDp * ctx.resources.displayMetrics.density
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), true).fetchSemanticsNodes(false)
        for (n in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (r in layouts) textProblems(r).forEach { add(it, "$where: \"${r.layoutInput.text.text.take(60)}\"") }
            if (n.positionInRoot.x + n.size.width > widthPx + 2 && n.size.width > 0)
                add("past the right edge", "$where: \"${text(n).take(60)}\"")
        }
        // layouts from the kit: a grid row is one height, and rows of one list keep the same trailing slots
        // (an arrow, stamp or chip missing in one row must leave its place empty, not move the rest)
        val cells = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(e7.gearscan.ui.CELL_TAG), true).fetchSemanticsNodes(false)
        for ((_, row) in cells.groupBy { Math.round(it.positionInRoot.y / 4f) }) {
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
        if (model.stack.size > 1 && model.sheet == null && !overlayShown && textBounds().keys.none { it.startsWith("‹") })
            add("no back link", "${model.screen::class.simpleName} (first at $where)")
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
