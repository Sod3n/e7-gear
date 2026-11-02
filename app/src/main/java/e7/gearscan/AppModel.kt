package e7.gearscan

import androidx.compose.runtime.setValue
import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.data.Box
import e7.gearscan.data.GameHero
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Request
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtNum
import e7.gearscan.data.Slot
import e7.gearscan.opt.Build
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.Setup
import e7.gearscan.state.blocking
import e7.gearscan.ui.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import e7.gearscan.ui.Confirm
import e7.gearscan.ui.Toast
import kotlin.concurrent.thread

sealed class Screen {
    data object ScanIntro : Screen()
    data object ScanData : Screen()
    data object Capture : Screen()
    data object ScanResult : Screen()
    data object Optimize : Screen()
    data object Heroes : Screen()
    data object Gear : Screen()
    data object Results : Screen()
    data object GhostBuild : Screen()
    data class Compare(val index: Int, val poolHero: Long? = null) : Screen()
    data class HeroDetail(val id: Long) : Screen()
    data class DiffItems(val kind: Int) : Screen()
    data object OverlayIntro : Screen()
    data class Feedback(val error: String? = null) : Screen()
    data object PoolRun : Screen()
    data object Upgrade : Screen()
    data object GearPotential : Screen()
    data object SellFilters : Screen()
    data object SellKept : Screen()
    /** Pieces that could make heroes with a setup better once finished: every hero, or the pieces for [heroId]. */
    data class HeroUpgrades(val heroId: Long? = null) : Screen()
    /** A pool run's results walked hero by hero, with reviewed marks. */
    data class PoolReview(val id: Long) : Screen()
    /** One pool hero's full setup; [setup]: walk the heroes with no build, else the ready ones. */
    data class PoolHero(val id: Long, val setup: Boolean) : Screen()
}

enum class SheetKind { PRESET, TARGETS, ADVANCED, BONUS, SETS, MAINS, GOALS, SHOW, HERO_PICK, ARTIFACT, POOL_PICK, POOL_WORSE, POOL_LOW, POOL_HEROES, POOL_LIST, POOL_TRADES, SCAN_RESTART, POTENTIAL_HELP, POTENTIAL_TARGETS, POTENTIAL_SLOTS, POTENTIAL_SETS, POTENTIAL_MAINS, POTENTIAL_OPTIONS, POTENTIAL_PIECE, SELL_SCORE, SELL_WEIGHTS, SELL_FLOORS, SELL_CUSTOMS, SELL_HEROES, SELL_INVENTORY, SELL_SLOTS, SELL_SETS, SELL_MAINS, SELL_LEVELS, SELL_QUALITY, SELL_DETAILS, SELL_HELP, SELL_SAFEGUARDS, SELL_EXTRA, SELL_WORTH, HERO_UPGRADE_PIECE, HERO_UPGRADE_HELP, HERO_UPGRADE_SETTINGS, HERO_UPGRADE_BUILD }

/** [back] is the sheet to return to when this one finishes; null closes. */
data class Sheet(val kind: SheetKind, val heroId: Long, val back: SheetKind? = null)

/** Saved Improve heroes results of another format are dropped on load. */
const val HERO_UPGRADES_FORMAT = 2
/** Simulated finishes the tracker checks a piece with: the chance it ends up an upgrade, and its weakest helping finishes. */
const val PIECE_SIMS = 120

/** The hero-upgrade check for the inputs behind [key] (see AppModel.heroUpgradeKey); [result] null until one finished. */
data class HeroUpgradeState(
    val scan: Long,
    val key: String,
    val result: e7.gearscan.opt.HeroUpgrades.Result?,
    val running: Boolean,
    val done: Int = 0,
    val total: Int = 0,
    val finishedAt: Long = 0,
    /** Each "check the rest" doubles the time per search. */
    val attempt: Int = 0,
)

class AppModel(app: Application) : AndroidViewModel(app) {
    val graph = getApplication<E7GearApp>().graph
    private val files = app.filesDir

    val content get() = graph.content.content
    val box get() = graph.scan.box
    val rev get() = graph.scan.rev
    private val configs get() = graph.heroes.configs
    val lastScan get() = graph.scan.lastScan
    /** With the "closest to targets" goal, results show match % unless another rating is picked. */
    var showMatch by mutableStateOf(true)

    var stack: List<Screen>
        get() = graph.nav.screens()
        internal set(v) { graph.nav.setScreens(v) }
    var sheet: Sheet?
        get() = graph.nav.sheet
        set(v) { graph.nav.sheet = v }

    var ready by mutableStateOf(false)
        private set

    // ---- navigation ----
    val screen get() = graph.nav.screen

    fun go(s: Screen) = graph.nav.go(s)
    fun replace(s: Screen) = graph.nav.replace(s)
    fun tab(s: Screen) = graph.nav.tab(s)
    /** A screen as a back link names it: its title. */
    fun screenLabel(s: Screen): String = when (s) {
        Screen.ScanIntro, Screen.Capture -> "Scan"
        Screen.ScanData -> "Scan & data"
        Screen.ScanResult -> "Imported"
        Screen.Optimize -> "Optimize"
        Screen.Gear -> "Gear"
        Screen.Heroes -> "Heroes"
        Screen.Results -> "Best 50"
        Screen.GhostBuild -> "Ghost build"
        is Screen.Compare -> s.poolHero?.let { id -> hero(id)?.let(::heroName) } ?: "Build #${s.index + 1}"
        is Screen.HeroDetail -> hero(s.id)?.let(::heroName) ?: "Hero"
        is Screen.DiffItems -> listOf("New items", "Enhanced", "Moved").getOrElse(s.kind) { "Imported" }
        Screen.OverlayIntro -> "Show over game"
        is Screen.Feedback -> "Feedback"
        Screen.PoolRun -> if (pool?.running == true) "Optimizing pool" else "Pool done"
        is Screen.PoolReview -> hero(s.id)?.let(::heroName) ?: "Pool done"
        is Screen.PoolHero -> hero(s.id)?.let(::heroName) ?: "Whole pool"
        Screen.Upgrade -> "Track upgrades"
        Screen.GearPotential -> "Find gear"
        Screen.SellFilters -> "Sell review"
        Screen.SellKept -> "Kept pieces"
        is Screen.HeroUpgrades -> s.heroId?.let { id -> hero(id)?.let(::heroName) } ?: "Improve heroes"
    }

    /** Where back goes from the current screen, by name. */
    fun backLabel(): String = stack.getOrNull(stack.size - 2)?.let(::screenLabel) ?: "Back"

    fun back(): Boolean = graph.nav.back()

    // every change is saved shortly after: Android may close the app while the player is in the game
    fun touch() = graph.scan.touch()

    // ---- heroes ----
    fun hero(id: Long): OwnedHero? = graph.heroes.hero(id)
    fun gameHero(h: OwnedHero): GameHero? = graph.heroes.gameHero(h)
    fun heroName(h: OwnedHero) = graph.heroes.heroName(h)
    fun face(h: OwnedHero) = graph.heroes.face(h)

    // kept for Improve heroes, the tracker and the checklists until those move
    private val pool get() = graph.pool.state
    private val poolTiers get() = graph.pool.tiers
    private val poolExtra get() = graph.pool.extra
    private val tiers get() = graph.heroes.tiers
    private fun poolMembers() = graph.pool.members()
    private fun tier(id: Long) = graph.heroes.tier(id)
    private fun request(id: Long) = graph.heroes.request(id)
    private fun currentBuild(id: Long) = graph.heroes.currentBuild(id)
    private fun matchFor(id: Long) = graph.heroes.matchFor(id)
    private fun matchOf(id: Long, b: Build) = graph.heroes.matchOf(id, b)
    private fun setupKey(id: Long) = graph.heroes.setupKey(id)
    private fun mainsOnly(id: Long, req: Request) = graph.heroes.mainsOnly(id, req)
    private fun rolls(id: Long) = graph.heroes.rolls(id)
    private fun worn(id: Long): Set<Long> = box?.gearOf(id)?.values.orEmpty().map { it.id }.toSet()
    private fun begin(out: Outcome) = graph.messages.show(out)
    private fun setup(id: Long, excluded: Set<Long> = emptySet(), canTakeFrom: (Long) -> Boolean = { tier(it) < tier(id) }) =
        graph.heroes.setup(id, excluded, canTakeFrom)
    private fun recordBuild(heroId: Long, b: Build, reforge: List<Long>, mods: List<Pair<Long, e7.gearscan.data.Mod>>?) =
        graph.heroes.recordBuild(heroId, b, reforge, mods)
    private fun equipPool(run: e7.gearscan.features.pool.PoolState?, reforge: List<Long>, mods: List<Pair<Long, e7.gearscan.data.Mod>>?) =
        graph.pool.equip(run, reforge, mods)

    var toast: Toast?
        get() = graph.messages.toast
        set(v) { graph.messages.toast = v }
    var confirm: Confirm?
        get() = graph.messages.dialog
        set(v) { graph.messages.dialog = v }
    fun askReset(title: String, body: String, run: () -> Unit) = graph.messages.confirm(title, body, "Reset") { run() }
    fun explain(title: String, body: String) = graph.messages.explain(title, body)

    fun showToast(text: String, action: String? = null, onAction: (() -> Unit)? = null) = graph.messages.showToast(text, action, onAction)

    // swiped away from recents: nothing is left to show the result, so stop rather than leave the service running
    override fun onCleared() {
        cancelAllWork()
        super.onCleared()
    }

    // ---- Sell review host (the feature reads the box, scan and tracker through this) ----
    private val sellHost = object : e7.gearscan.features.sellreview.SellHost {
        override val records get() = upgrades.records
        override val contentVersion get() = content.version
        override fun startQueue(rows: List<Pair<Item, e7.gearscan.opt.SellSearch.Row>>, query: e7.gearscan.data.SellQuery, openOverlay: Boolean) {
            val entries = rows.map { (original, row) ->
                val set = content.set(original.set)
                original to e7.gearscan.overlay.UpgradeTracker.QueueEntry(original.id, null, set?.name ?: original.set.removeSuffix("Set"), set?.icon,
                    row.goal.takeIf { query.scoreFilter }, row.rolls, upgrades.records[original.id]?.takeIf { it.scan == lastScan }?.history?.size ?: 1,
                    row.goal.reforgeAllowed, row.goal.supported)
            }
            upgrades.startQueue(entries, lastScan, query)
            configureUpgrade(); go(Screen.Upgrade)
            if (openOverlay) openUpgradeOverlay()
        }
        override fun rollOnChanged(rollOn: Double) { upgrades.rollOn = rollOn }
        override fun touch() { this@AppModel.touch() }
    }
    init { graph.sellReview.host = sellHost }

    private fun refreshScoreGoal() {
        val q=upgrades.scoreQuery?.takeIf { it.scoreFilter }?:return
        val context=graph.sellReview.context(q)?:return
        val ids=upgrades.queue.map { it.id }.toSet()+listOfNotNull(upgrades.selected)
        upgrades.refreshScoreGoals(context.items.filter { it.id in ids }.associate { it.id to context.goal(it) })
    }

    // ---- upgrade observations (separate from the scan) ----
    val upgrades = e7.gearscan.overlay.UpgradeTracker(graph.tracker.upgrades.file) { graph.tracker.upgrades.write(it) }
    init { graph.marks.onChange = { upgrades.cleared = reviewedIds } }

    init {
        graph.findGear.source = object : e7.gearscan.features.findgear.FindGearFeature.Source {
            override val records get() = upgrades.records
            override fun setLabel(key: String) = content.set(key).let { (it?.name ?: key.removeSuffix("Set")) to it?.icon }
            override fun track(item: Item, goal: e7.gearscan.opt.GearPotential.Goal, openOverlay: Boolean) {
                upgrades.stopCheck()
                upgrades.select(item, lastScan)
                configureUpgrade()
                upgrades.useGoal(goal)
                go(Screen.Upgrade)
                if (openOverlay) openUpgradeOverlay()
            }
            override fun trackQueue(entries: List<Pair<Item, e7.gearscan.overlay.UpgradeTracker.QueueEntry>>, openOverlay: Boolean) {
                upgrades.stopCheck()
                upgrades.startQueue(entries, lastScan)
                configureUpgrade()
                go(Screen.Upgrade)
                if (openOverlay) openUpgradeOverlay()
            }
        }
    }

    fun openUpgradeOverlay() {
        val r = upgrades.record ?: return
        if (r.scan != lastScan || r.item.id !in (box?.itemsById ?: emptyMap())) {
            upgrades.error = "Your scan changed. Choose the gear again."; return
        }
        configureUpgrade()
        e7.gearscan.overlay.OverlayState.upgrade = upgrades
        e7.gearscan.overlay.OverlayState.expanded = false
        if (graph.settings.overGameNative) { nativeOverGame(); checkUpgrade(); return }
        if (e7.gearscan.overlay.OverlayState.canDraw(getApplication())) {
            e7.gearscan.overlay.OverlayService.start(getApplication()); checkUpgrade(); graph.scan.openGame()
        } else go(Screen.OverlayIntro)
    }

    private fun syncClearedUpgradeQueue() {
        upgrades.cleared=reviewedIds
        if(upgrades.isSellQueue&&upgrades.queueScan==lastScan&&upgrades.selected in reviewedIds) {
            upgrades.pendingQueue.firstOrNull()?.let { e->box?.itemsById?.get(e.id)?.let { item->
                if(upgrades.records[e.id]?.scan==lastScan)upgrades.select(item,lastScan,keepQueue=true)
            } }
        }
    }
    private fun configureUpgrade() {
        syncClearedUpgradeQueue()
        upgrades.canUndoSold=graph.marks.lastSale(lastScan,upgrades.queue.map { it.id }.toSet())!=null
        upgrades.markSold=if(upgrades.scoreQuery!=null)({ id->markOverlaySold(id) }) else null
        upgrades.markKept=if(upgrades.scoreQuery!=null)({ id->markOverlaySold(id,keep=true) }) else null
        upgrades.rollOn=graph.sellReview.query.rollOn
        upgrades.esFor=upgrades.scoreQuery?.let { q->{ item:Item->
            val b=box;val ctx=graph.sellReview.context(q)
            if(b==null||ctx==null)null else e7.gearscan.opt.EsForecast.of(item,e7.gearscan.data.InventoryOrder.score(item,if(item.enhance==b.itemsById[item.id]?.enhance)ctx.raw[item.id] else null),
                ctx.esBar(item.slot),q.reforge,b.canReforge(item.id),ctx.coverage(item),q.forecastMs)
        } }
        upgrades.undoSold=if(upgrades.scoreQuery!=null)({ undoOverlaySold() }) else null
        refreshScoreGoal()
        val item = upgrades.record?.item ?: return
        upgrades.setIcon = content.set(item.set)?.icon
        upgrades.setName = content.set(item.set)?.name
        upgrades.requestCheck = ::checkUpgrade
        upgrades.canReforge = { id -> box?.canReforge(id) == true }
        upgrades.equipOn = ::equipTracked
        upgrades.switchTo = { id -> selectUpgrade(id); checkUpgrade() }
        upgrades.rebuilds = heroUpgradeRebuilds
        upgrades.setRebuilds = { on -> useHeroUpgradeRebuilds(on); upgrades.rebuilds = on; checkUpgrade() }
        upgrades.selectQueue = { index ->
            val entry = upgrades.pendingQueue.getOrNull(index)
            val original = entry?.let { box?.itemsById?.get(it.id) }?.takeIf { it.id !in clearedIds }
            if(upgrades.queueScan!=lastScan)upgrades.error="Your scan changed. Refresh the matches in E7 Gear."
            else if (original != null) { upgrades.select(original, lastScan, keepQueue = true); configureUpgrade() }
            else upgrades.error = "This piece is no longer in the inventory. Refresh the matches in E7 Gear."
        }
        upgrades.returnToApp = {
            // in the phone's floating window the app is already here: show all of it
            if (floating) floatingPanel = false
            else getApplication<Application>().startActivity(Intent(getApplication(), MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Acknowledges a sale made in the game; never sends a game command. */
    fun markOverlaySold(expectedId:Long,keep:Boolean=false) {
        val t=upgrades;val record=t.record?:return
        if(t.editing||t.scoreQuery==null||t.selected!=expectedId||expectedId in reviewedIds)return
        if(t.queueScan!=lastScan||record.scan!=lastScan||box?.itemsById?.containsKey(expectedId)!=true||t.queue.none { it.id==expectedId }) {
            t.error="Your scan changed. Refresh the matches before marking this piece.";return
        }
        val index=t.queueIndex.coerceAtLeast(0)
        graph.marks.recordSold(expectedId,lastScan,keep)
        t.invalidate();save()
        val next=t.pendingQueue.getOrNull(index)?:t.pendingQueue.lastOrNull()
        next?.let { e->box?.itemsById?.get(e.id)?.let { t.select(it,lastScan,keepQueue=true) } }
        configureUpgrade()
    }
    fun undoOverlaySold() {
        val t=upgrades
        if(t.editing)return
        val mark=graph.marks.lastSale(lastScan,t.queue.map { it.id }.toSet())?:return
        if(box?.itemsById?.containsKey(mark.id)!=true) { t.error="That piece is no longer in your scan.";return }
        graph.marks.undoSale(mark)
        t.invalidate();save()
        t.select(box!!.itemsById.getValue(mark.id),lastScan,keepQueue=true);configureUpgrade()
    }

    fun selectUpgrade(id: Long) {
        val item = box?.itemsById?.get(id) ?: return
        if (!e7.gearscan.data.UpgradeRecord.supported(item)) return
        upgrades.select(item, lastScan)
        configureUpgrade()
    }

    fun checkUpgrade() {
        if (upgrades.hasTarget) { refreshScoreGoal();upgrades.busy = false; return }
        val r = upgrades.record ?: return
        if (r.scan != lastScan || box?.itemsById?.containsKey(r.item.id) != true) {
            upgrades.error = "Your scan changed. Choose the piece again in Track upgrades."; return
        }
        val observed = upgrades.records.values.filter { it.scan == lastScan }.associate { it.item.id to it.item }
        val b = Box(JSONObject(box!!.toText()).put("items", JSONArray(box!!.items.map { item ->
            e7.gearscan.data.UpgradeRecord.itemJson(observed[item.id] ?: item)
        })))
        val setups = gearSetups(b, inUse(b) + clearedIds).map { s ->
            val cfg = HeroConfig.fromJson(s.cfg.toJson())
            Setup(s.hero, s.game, cfg, s.req.copy(), b, content, s.canTakeFrom, s.excluded - r.item.id)
        }
        val rebuilds = heroUpgradeRebuilds
        val raw = "upgrade-v3-piece|$rebuilds|" + b.toText() + content.version + r.toJson().toString() + setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() }
        val sig = sha256(raw)
        // each hero's best build without the piece holds for every step recorded on it
        val withoutKey = sha256("without-v1|${r.item.id}|" + JSONArray(b.items.filter { it.id != r.item.id }.map { e7.gearscan.data.UpgradeRecord.itemJson(it) }) +
            content.version + setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() })
        if (pieceWithout?.first != withoutKey) pieceWithout = withoutKey to java.util.concurrent.ConcurrentHashMap()
        val without = pieceWithout!!.second
        val resume = upgrades.resumable(sig)
        if (setups.isEmpty() || resume.size == setups.size) { upgrades.begin(sig, setups.size); upgrades.busy = false; return }
        val out = graph.tracker.check.start {
            val scope = this
            val alive = { scope.isActive }
            upgrades.begin(sig, setups.size)
            onCancel { upgrades.busy = false }
            try {
                val t0 = System.nanoTime()
                val done = java.util.Collections.synchronizedList(ArrayList<e7.gearscan.opt.UpgradeCheck.HeroResult>(resume))
                withContext(Dispatchers.Default) {
                    e7.gearscan.opt.HeroUpgrades(PIECE_SIMS).piece(setups, r.item, without, upgrades.attempt, resume.map { it.id }.toSet(),
                        cancelled = { !alive() }) { pr ->
                        done += pieceAdvice(pr, rebuilds)
                        val now = synchronized(done) { done.toList() }
                        viewModelScope.launch {
                            if (alive()) {
                                upgrades.checkpoint(now)
                                scope.progress(now.size, setups.size, "${now.size}/${setups.size} heroes")
                            }
                        }
                    }
                }
                upgrades.checkpoint(synchronized(done) { done.toList() }); upgrades.busy = false
                android.util.Log.i("E7Upgrade", "checked ${upgrades.results.size}/${setups.size} heroes, ${upgrades.hitCount}/${upgrades.total} finishes for piece " +
                    "${r.item.id} +${r.item.enhance}, %.1f s".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9))
                // still the best bet? the same heroes' other unfinished pieces for this slot, through the same check
                val tops = upgrades.results.filter { it.hits.isNotEmpty() || it.swapNow }.sortedByDescending { it.hits.size }.take(3)
                if (r.item.enhance < 15 && tops.isNotEmpty()) {
                    val alts = withContext(Dispatchers.Default) { compareSlot(setups.filter { s -> tops.any { it.id == s.hero.id } }, r.item, b, rebuilds) }
                    upgrades.checkpoint(upgrades.results.map { h ->
                        alts[h.id]?.let { (lines, better) -> h.copy(alts = lines.map { it.second }, better = better,
                            betterText = lines.firstOrNull { it.first == better.firstOrNull() }?.second.orEmpty()) } ?: h })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                upgrades.busy = false; upgrades.error = "Check stopped. Recorded steps are saved; tap Recheck to continue."
            }
        }
        if (out is Outcome.Failed) upgrades.error = out.text
    }

    private var pieceWithout: Pair<String, java.util.concurrent.ConcurrentHashMap<Long, e7.gearscan.opt.HeroUpgrades.Base>>? = null

    /**
     * [current] and the hero's other unfinished pieces for its slot, through one check with the same finishes count and
     * reference (what it wears, or with rebuilds its best build now), ranked by expected gain: the share of finishes that
     * help times how much. Lines (piece id, text) best first, and the ids ranked ahead of [current].
     */
    private fun compareSlot(heroes: List<Setup>, current: Item, b: Box, rebuilds: Boolean): Map<Long, Pair<List<Pair<Long, String>>, List<Long>>> {
        val others = b.items.filter { it.slot == current.slot && it.id != current.id && it.enhance < 15 && it.id !in clearedIds &&
            e7.gearscan.data.UpgradeRecord.supported(it) }
        val res = e7.gearscan.opt.HeroUpgrades().run(heroes, (listOf(current) + others).sortedBy { it.id })
        return heroes.mapNotNull { s -> rankSlot(s, current, b, rebuilds, res)?.let { s.hero.id to it } }.toMap()
    }

    private fun rankSlot(s: Setup, current: Item, b: Box, rebuilds: Boolean, res: e7.gearscan.opt.HeroUpgrades.Result): Pair<List<Pair<Long, String>>, List<Long>>? {
        val base = res.bases[s.hero.id] ?: return null
        val ref = (if (rebuilds) base.value else base.worn) ?: return null
        val refBase = e7.gearscan.opt.HeroUpgrades.Base(base.id, ref, emptyList(), false, base.unit, 0)
        fun counted(p: e7.gearscan.opt.HeroUpgrades.Pick) = (if (rebuilds) p.values else p.swaps.map { it.second })
            .filter { it > ref + e7.gearscan.opt.HeroUpgrades.MIN_GAIN * base.unit }.sortedDescending()
        val ranked = res.picks.filter { it.hero == s.hero.id }.map { p -> p to counted(p) }.map { (p, v) -> Triple(p, v, v.sumOf { (it - ref) / base.unit } / res.sims) }
            .filter { it.third > 0 || it.first.piece == current.id }.sortedByDescending { it.third }
        val mine = ranked.firstOrNull { it.first.piece == current.id }?.third ?: 0.0
        val lines = ranked.take(5).map { (p, v, _) ->
            val q = if (p.piece == current.id) current else b.itemsById.getValue(p.piece)
            val name = "${content.set(q.set)?.name ?: q.set.removeSuffix("Set")} ${q.slot.label} +${q.enhance}"
            val where = if (p.piece == current.id) " (this one)" else ""
            p.piece to "$name$where · ${v.size * 100 / res.sims}%" + (v.getOrNull(v.size / 2)?.let { " · usually ${heroGainLabel(s.hero.id, refBase, it)}" } ?: "")
        }
        return lines to ranked.filter { it.first.piece != current.id && it.third > mine }.map { it.first.piece }
    }

    /** One hero's part of the tracker's advice, with its gains and weakest helping finishes as text. */
    private fun pieceAdvice(pr: e7.gearscan.opt.HeroUpgrades.PieceResult, rebuilds: Boolean): e7.gearscan.opt.UpgradeCheck.HeroResult {
        val hits = if (rebuilds) pr.hits else pr.swapHits
        val values = hits.values.sortedDescending()
        val now = if (rebuilds) pr.now else pr.swapNow
        val nowValue = if (rebuilds) pr.bar.value else pr.swapNowValue
        val build = (if (rebuilds) pr.rebuildBuild else pr.swapBuild)?.sortedBy { it.slot.ordinal }
        val lines = build?.let { b ->
            // the check's own ids for the piece are negative
            val from = buildFrom(pr.hero, b, Long.MIN_VALUE)
            b.mapIndexed { i, q -> "${q.slot.label}: ${content.set(q.set)?.name ?: q.set.removeSuffix("Set")} ${q.mainType.short} · ${from[i]}" } +
                "Sets: ${e7.gearscan.opt.HeroUpgrades.setsLabel(b, content)}"
        }.orEmpty()
        return e7.gearscan.opt.UpgradeCheck.HeroResult(pr.hero, hero(pr.hero)?.let(::heroName) ?: "", hits.keys.sorted(), now, false, pr.unknown,
            pr.provisional, if (now && nowValue != null) (if (rebuilds) pr.without else pr.worn ?: pr.without).let { b -> b.value?.let { (nowValue - it) / b.unit } } ?: 1.0 else null,
            if (rebuilds) pr.changes else pr.swapChanges, pr.total,
            (if (rebuilds) pr.minimal else pr.swapMinimal).map { q -> q.subs.joinToString(" · ") { "${it.type.short} ${it.type.format(it.value)}" } },
            values.getOrNull(values.size / 2)?.let { v -> heroGainLabel(pr.hero, if (rebuilds) pr.bar else pr.worn?.let { w ->
                e7.gearscan.opt.HeroUpgrades.Base(w.id, pr.swapRef, w.items, false, w.unit, 0, w.worn) } ?: pr.bar, v) }.orEmpty(),
            if (now && nowValue != null) heroGainLabel(pr.hero, if (rebuilds) pr.without else pr.worn ?: pr.without, nowValue) else "", pr.swapNow, lines)
    }

    /** A build value over [base] as the player reads it: match points on targets, else the goal's own unit. */
    fun heroGainLabel(id: Long, base: e7.gearscan.opt.HeroUpgrades.Base, value: Double): String {
        val v0 = base.value ?: return "makes a build"
        val req = configs[id]?.request ?: return ""
        val d = value - v0
        fun one(x: Double) = fmtNum(Math.round(x * 10) / 10.0)
        return when (req.goal) {
            Goal.TARGETS -> mainsOnly(id, req)?.let { floor -> rolls(id)?.let { roll ->
                e7.gearscan.data.Match.start(floor, req.targets, req.weights, roll).takeIf { it > 0 }?.let { "+${one(100 * d / it)} match" } } }
                ?: "+${one(d / base.unit)} rolls"
            Goal.SPEED -> "+${one(d)} speed"
            Goal.GEAR_SCORE -> "+${one(d)} score"
            Goal.DAMAGE -> "+${one(100 * d / kotlin.math.abs(v0))}% damage"
            Goal.EHP -> "+${one(100 * d / kotlin.math.abs(v0))}% EHP"
            Goal.CP -> "+${one(100 * d / kotlin.math.abs(v0))}% CP"
        }
    }

    /**
     * The tracked piece as recorded, now worn by [heroId]: its stats and owner go into the box (a new scan replaces them), the
     * hero's piece in that slot goes to the bag. Undo in the tracker puts everything back.
     */
    fun equipTracked(heroId: Long) {
        val b = box ?: return
        val r = upgrades.record ?: return
        if (r.scan != lastScan || b.itemsById[r.item.id] == null) { upgrades.error = "Your scan changed. Choose the piece again."; return }
        val owner = b.itemsById.getValue(r.item.id).equippedOn
        val displaced = b.gearOf(heroId)[r.item.slot]?.takeIf { it.id != r.item.id }
        val before = b.writeStats(r.item) ?: return
        val next = Box(b.json)
        next.itemsById[r.item.id]?.let { next.setEquipped(it, heroId) }
        displaced?.let { d -> next.itemsById[d.id]?.let { next.setEquipped(it, null) } }
        graph.scan.replaceBox(next); touch(); saveBox(); save()
        val name = hero(heroId)?.let(::heroName) ?: "the hero"
        upgrades.equippedOn = name
        upgrades.undoEquip = {
            box?.let { cur ->
                cur.restore(r.item.id, before)
                val back = Box(cur.json)
                back.itemsById[r.item.id]?.let { back.setEquipped(it, owner) }
                displaced?.let { d -> back.itemsById[d.id]?.let { back.setEquipped(it, heroId) } }
                graph.scan.replaceBox(back); touch(); saveBox(); save()
            }
            upgrades.undoEquip = null; upgrades.equippedOn = null
        }
    }

    // ---- marks ----
    val clearedIds get() = graph.marks.cleared
    val reviewedIds get() = graph.marks.reviewed

    /** Pieces worn by heroes of the pool run stay where they are; everything else is spare. */
    private fun inUse(b: Box): Set<Long> {
        val members = poolMembers().map { it.id }.toSet()
        return b.items.filter { it.equippedOn in members }.map { it.id }.toSet()
    }

    /** Every hero with a setup, each free to use its own gear and the spares. */
    private fun gearSetups(b: Box, inUse: Set<Long>): List<Setup> = b.heroes.mapNotNull { h ->
        val g = gameHero(h) ?: return@mapNotNull null
        val cfg = configs[h.id] ?: return@mapNotNull null
        val req = cfg.request?.copy() ?: return@mapNotNull null
        if (req.goal == Goal.TARGETS && req.targets.all { it.isNaN() }) return@mapNotNull null
        req.lowerPriorityOnly = false; req.keepCurrent = false; req.maxChanges = 6; req.mods = false
        Setup(h, g, cfg, req, b, content, { true }, inUse - worn(h.id))
    }

    // ---- improve heroes: pieces below +15 that could make heroes with a setup better once finished ----
    var heroUpgrades: HeroUpgradeState? by mutableStateOf(null)
        internal set
    /** A stopped or killed check for the current gear and setups waits in its progress file. */
    var heroUpgradesResumable by mutableStateOf(false)
        private set
    /** Smallest gain a finish must bring to be listed, in rolls ([e7.gearscan.opt.HeroUpgrades.unit]). */
    var heroUpgradeMinGain get() = graph.improveHeroes.minGain
        private set(v) { graph.improveHeroes.minGain = v }
    private var heroKeyMemo: Pair<List<Any?>, String>? = null

    fun useHeroUpgradeMinGain(rolls: Double) { heroUpgradeMinGain = rolls; touch() }

    /** Off: a finish counts only put in place of the hero's piece in that slot, nothing else moving. On: any build the search finds. */
    var heroUpgradeRebuilds get() = graph.improveHeroes.rebuilds
        private set(v) { graph.improveHeroes.rebuilds = v }
    fun useHeroUpgradeRebuilds(on: Boolean) { heroUpgradeRebuilds = on; touch() }

    /** A hero's final build with a piece upgraded: six slots, where each piece comes from, and the stats against what it wears. */
    class FinalBuild(val hero: OwnedHero, val pieceId: Long, val items: List<Item>, val from: List<String>, val sets: Pair<String, String>,
                     val stats: List<Triple<Stat, Double, Double>>, val gain: String, val note: String)
    var finalBuild: FinalBuild? by mutableStateOf(null)
        private set
    var finalBuildBusy by mutableStateOf(false)
        private set

    /** Where each piece of [items] comes from for [heroId]: this piece, stays, from the bag, or from another hero. */
    fun buildFrom(heroId: Long, items: List<Item>, pieceId: Long): List<String> {
        val worn = box?.gearOf(heroId).orEmpty()
        return items.map { when {
            it.id == pieceId || it.id < 0 -> "This piece, upgraded"
            worn[it.slot]?.id == it.id -> "Stays"
            it.equippedOn == null -> "From the bag"
            else -> "From ${hero(it.equippedOn!!)?.let(::heroName) ?: "a hero not in the scan"}"
        } }
    }

    /** The stats the hero's setup scores, now (worn) and with [items]. */
    private fun scoredStats(s: Setup, items: List<Item>): List<Triple<Stat, Double, Double>> {
        val now = s.build(s.current().values.toList()).final.stats
        val after = s.build(items).final.stats
        val obj = e7.gearscan.opt.Objectives.of(s.req, s.hb)
        val cares = (obj.uses.filter { it < 8 } + (0 until 8).filter { !s.req.mins[it].isNaN() }).distinct()
        return Stat.entries.filter { it.ordinal in cares }.map { Triple(it, now[it.ordinal], after[it.ordinal]) }
    }

    /** Opens the final build of [row]'s usual finish for [heroId], worked out off the main thread. */
    fun openFinalBuild(heroId: Long, row: HeroUpgradeRow) {
        val r = heroUpgrades?.result ?: return
        val h = hero(heroId) ?: return
        finalBuild = null; finalBuildBusy = true
        val rebuilds = heroUpgradeRebuilds
        viewModelScope.launch {
            val fb = withContext(Dispatchers.Default) {
                val (setups, pieces, _) = heroUpgradeInputs() ?: return@withContext null
                val s = setups.firstOrNull { it.hero.id == heroId } ?: return@withContext null
                val piece = pieces.firstOrNull { it.id == row.pick.piece } ?: return@withContext null
                val j = row.usual.takeIf { it >= 0 } ?: 0
                val f = e7.gearscan.opt.Finish.of(piece, r.sims)[j]
                val finish = Item(piece.id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, f.subs, piece.locked, piece.equippedOn, main15 = f.main15)
                val worn = s.current().values.toList().takeIf { it.size == 6 }
                val items = if (rebuilds) e7.gearscan.opt.HeroUpgrades.rebuild(s, finish)?.items
                    else worn?.map { if (it.slot == piece.slot) finish else it }
                items ?: return@withContext null
                FinalBuild(h, piece.id, items.sortedBy { it.slot.ordinal }, buildFrom(heroId, items.sortedBy { it.slot.ordinal }, piece.id),
                    e7.gearscan.opt.HeroUpgrades.setsLabel(s.current().values, content) to e7.gearscan.opt.HeroUpgrades.setsLabel(items, content),
                    scoredStats(s, items), row.typical, if (rebuilds) "The best build with a usual finish of this piece" else "What the hero wears, with a usual finish of this piece in its slot")
            }
            finalBuild = fb; finalBuildBusy = false
        }
    }

    private var usualInputs: Pair<List<Any?>, Pair<List<Setup>, List<Item>>>? = null
    /** Stat changes per (hero, piece, rebuilds), filled ahead in the background so rows draw with them. */
    val heroUpgradeChanges = mutableStateMapOf<Triple<Long, Long, Boolean>, List<Pair<Stat, Double>>>()
    private var changesFor: Any? = null

    /** Works out [rows]' stat changes off the main thread, top rows first; rows already known are skipped. */
    suspend fun warmHeroUpgradeChanges(rows: List<Pair<Long, HeroUpgradeRow>>) {
        val r = heroUpgrades?.result ?: return
        val key = listOf(r, lastScan, rev, clearedIds)
        if (changesFor != key) { heroUpgradeChanges.clear(); changesFor = key }
        val rebuilds = heroUpgradeRebuilds
        for ((hero, row) in rows) {
            val k = Triple(hero, row.pick.piece, rebuilds)
            if (k !in heroUpgradeChanges) heroUpgradeChanges[k] = heroUpgradeStatChanges(hero, row)
        }
    }

    /**
     * The hero's stat changes from [row], for cards: with This piece only, the row's usual finish put in the hero's slot
     * against what it wears; with rebuilds, the best finish's changes the check stored. Only stats the setup scores.
     */
    suspend fun heroUpgradeStatChanges(heroId: Long, row: HeroUpgradeRow): List<Pair<Stat, Double>> {
        if (heroUpgradeRebuilds) return row.changes.split(" · ").mapNotNull { part ->
            val name = part.substringBeforeLast(' '); val v = part.substringAfterLast(' ').toDoubleOrNull() ?: return@mapNotNull null
            Stat.entries.firstOrNull { it.short == name }?.let { it to v }
        }
        val r = heroUpgrades?.result ?: return emptyList()
        val key = listOf(r, lastScan, rev, clearedIds)
        return withContext(Dispatchers.Default) {
            val (setups, pieces) = synchronized(this@AppModel) {
                usualInputs?.takeIf { it.first == key }?.second ?: run {
                    val (s, p, _) = heroUpgradeInputs() ?: return@withContext emptyList()
                    (s to p).also { usualInputs = key to it }
                }
            }
            val s = setups.firstOrNull { it.hero.id == heroId } ?: return@withContext emptyList()
            val piece = pieces.firstOrNull { it.id == row.pick.piece } ?: return@withContext emptyList()
            val f = e7.gearscan.opt.Finish.of(piece, r.sims)[row.usual.takeIf { it >= 0 } ?: 0]
            val finish = Item(piece.id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, f.subs, piece.locked, piece.equippedOn, main15 = f.main15)
            val worn = s.current().values.toList().takeIf { it.size == 6 } ?: return@withContext emptyList()
            scoredStats(s, worn.map { if (it.slot == piece.slot) finish else it }).mapNotNull { (stat, now, after) ->
                val d = Math.round(after - now).toDouble()
                if (d == 0.0) null else stat to d
            }
        }
    }

    /** The heroes the check covers: every hero with a setup, as in [gearSetups]. */
    fun heroUpgradeHeroes(): List<OwnedHero> = box?.heroes.orEmpty().filter { h ->
        gameHero(h) != null && configs[h.id]?.request?.let { r -> r.goal != Goal.TARGETS || r.targets.any { !it.isNaN() } } == true
    }

    private fun sha256(raw: String) = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(java.util.Locale.US, it) }

    /** What the answer depends on: scan, recorded upgrades, cleared pieces, the pool's protected gear, setups, content. */
    fun heroUpgradeKey(): String {
        val memoKey = listOf(box, lastScan, upgrades.records, clearedIds, poolTiers, poolExtra, tiers, rev, content.version)
        heroKeyMemo?.takeIf { it.first == memoKey }?.let { return it.second }
        val raw = "hero-upgrades-v2|$lastScan|" + upgrades.records.values.filter { it.scan == lastScan }.sortedBy { it.item.id }.joinToString { it.toJson().toString() } +
            "|${clearedIds.sorted()}|${poolMembers().map { it.id }.sorted()}|${content.version}|" +
            heroUpgradeHeroes().joinToString(",") { "${it.id}=${setupKey(it.id)}" }
        return sha256(raw).also { heroKeyMemo = memoKey to it }
    }

    /** The shown result was made for other gear or setups: still readable, but "Check again" gives the current answer. */
    val heroUpgradesStale: Boolean get() = heroUpgrades?.let { !it.running && it.result != null && it.key != heroUpgradeKey() } == true

    /** The box as recorded (manual upgrades of this scan applied, cleared pieces out), the heroes, the pieces to try, and a full fingerprint. */
    private fun heroUpgradeInputs(): Triple<List<Setup>, List<Item>, String>? {
        val b0 = box ?: return null
        val observed = upgrades.records.values.filter { it.scan == lastScan }.associate { it.item.id to it.item }
        val b = Box(JSONObject(b0.toText()).put("items", JSONArray(b0.items.filter { it.id !in clearedIds }.map { item ->
            e7.gearscan.data.UpgradeRecord.itemJson(observed[item.id] ?: item)
        })))
        val setups = gearSetups(b, inUse(b))
        val pieces = b.items.filter { it.enhance < 15 && e7.gearscan.data.UpgradeRecord.supported(it) }.sortedBy { it.id }
        val full = sha256("hero-upgrades-v2|${e7.gearscan.opt.HeroUpgrades.SIMS}|" + b.toText() + content.version +
            setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() })
        return Triple(setups, pieces, full)
    }

    private fun writeHeroProgress(full: String, light: String, attempt: Int, p: e7.gearscan.opt.HeroUpgrades.Progress, finished: Boolean) =
        synchronized(graph.improveHeroes.progress) {
            val text = JSONObject().put("full", full).put("light", light).put("attempt", attempt).put("finished", finished).put("progress", p.toJson()).toString()
            graph.improveHeroes.progress.write(text)
        }

    /** [more]: another pass over the searches that ran out of time, with twice the time each; everything settled is kept. */
    fun startHeroUpgrades(more: Boolean = false) {
        if (heroUpgrades?.running == true) return
        val (setups, pieces, full) = heroUpgradeInputs() ?: return
        val light = heroUpgradeKey()
        val saved = runCatching { JSONObject(graph.improveHeroes.progress.read().orEmpty()) }.getOrNull()?.takeIf { it.optString("full") == full }
        val resume = saved?.optJSONObject("progress")?.let { runCatching { e7.gearscan.opt.HeroUpgrades.Progress.of(it) }.getOrNull() }
            ?: e7.gearscan.opt.HeroUpgrades.Progress()
        val attempt = (saved?.optInt("attempt") ?: 0) + if (more && saved != null) 1 else 0
        val scan = lastScan
        begin(graph.improveHeroes.run.start {
            val scope = this
            val alive = { scope.isActive }
            heroUpgrades = HeroUpgradeState(scan, light, heroUpgrades?.result?.takeIf { heroUpgrades?.key == light }, running = true, attempt = attempt)
            heroUpgradesResumable = false
            val run = e7.gearscan.opt.HeroUpgrades()
            onCancel { run.cancel(); heroUpgradesStopped() }
            var written = 0L
            // the writes after the run happen even when it was stopped: they are what makes it resumable
            val state = blocking("hero-upgrades") {
                val t0 = System.nanoTime()
                val r = runCatching {
                    run.run(setups, pieces, attempt, cancelled = { !alive() }, progress = { done, total ->
                        viewModelScope.launch { if (alive()) heroUpgrades = heroUpgrades?.copy(done = done, total = total) }
                        scope.progress(done, total, "${done * 100 / total.coerceAtLeast(1)}%")
                    }, resume = resume) {
                        // a few seconds of work at most is lost if the app is killed
                        if (System.currentTimeMillis() - written > 3000) { written = System.currentTimeMillis(); writeHeroProgress(full, light, attempt, it, false) }
                    }
                }.onFailure { android.util.Log.e("E7Heroes", "hero upgrade check failed", it) }.getOrNull()
                val finished = r != null && alive()
                // a newer check owns the file
                if (alive() || !graph.improveHeroes.run.busy) writeHeroProgress(full, light, attempt, resume, finished)
                val done = r?.takeIf { finished }?.let { HeroUpgradeState(scan, light, it, running = false, finishedAt = System.currentTimeMillis(), attempt = attempt) }
                if (done != null) {
                    runCatching {
                        graph.improveHeroes.result.write(JSONObject().put("format", HERO_UPGRADES_FORMAT).put("scan", scan).put("key", light)
                            .put("finishedAt", done.finishedAt).put("attempt", attempt).put("result", r.toJson()).toString())
                    }
                    android.util.Log.i("E7Heroes", "hero upgrades: ${setups.size} heroes, ${pieces.size} pieces, ${r.picks.size} picks, " +
                        "${r.unknownSteps} open steps, attempt $attempt, %.1f s".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9))
                }
                done
            }
            if (state == null) { heroUpgrades = heroUpgrades?.copy(running = false); heroUpgradesResumable = true; return@start }
            heroUpgrades = state
            notifyHeroUpgradesDone()
        })
    }

    private fun heroUpgradesStopped() {
        heroUpgrades = heroUpgrades?.copy(running = false)?.takeIf { it.result != null }
        heroUpgradesResumable = true
    }

    fun stopHeroUpgrades() = graph.improveHeroes.run.cancel()

    private fun notifyHeroUpgradesDone() {
        val app = getApplication<Application>()
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("pool", "Pool optimize", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(app, 11, Intent(app, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = heroUpgrades?.result?.let { r -> heroUpgradeView(r).count { it.rows.isNotEmpty() } } ?: 0
        nm.notify(11, android.app.Notification.Builder(app, "pool").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Hero upgrade check done").setContentText("$n heroes can get better · open to see the pieces")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    /** A piece for a hero as listed: [hits] of [sims] finishes gain at least the minimum; [expected] = their gains summed over all finishes, in rolls. */
    /** [usual]: the finish (index among its piece's) behind [typical], for the final build; [changes] of the best one. */
    class HeroUpgradeRow(val pick: e7.gearscan.opt.HeroUpgrades.Pick, val hits: Int, val sims: Int, val typical: String, val best: String, val expected: Double,
                         val usual: Int = -1, val changes: String = "") {
        val chance get() = hits * 100 / sims
    }

    /** [bestNow]: match % of the best build the hero can make now (targets goal), the bar every finish is measured against. */
    class HeroUpgradeHero(val hero: OwnedHero, val base: e7.gearscan.opt.HeroUpgrades.Base, val rows: List<HeroUpgradeRow>, val bestNow: Int?, val open: Int)

    /** Every checked hero (higher tiers first, then by what its best piece is worth), each with the pieces that gain at least [minGain] rolls. */
    fun heroUpgradeView(r: e7.gearscan.opt.HeroUpgrades.Result, minGain: Double = heroUpgradeMinGain, rebuilds: Boolean = heroUpgradeRebuilds): List<HeroUpgradeHero> {
        val byHero = r.picks.groupBy { it.hero }
        return r.bases.values.mapNotNull { base ->
            val h = hero(base.id) ?: return@mapNotNull null
            val req = configs[h.id]?.request ?: return@mapNotNull null
            val scale = if (req.goal == Goal.TARGETS) mainsOnly(h.id, req)?.let { floor -> rolls(h.id)?.let { roll ->
                e7.gearscan.data.Match.start(floor, req.targets, req.weights, roll).takeIf { it > 0 }?.let { 100 / it } } } else null
            // the starting value: the best build now, or (nothing else moving) what the hero wears
            val ref = if (rebuilds) base.value else base.worn
            fun label(v: Double): String {
                val v0 = ref ?: return "makes a build"
                val d = v - v0
                fun one(x: Double) = fmtNum(Math.round(x * 10) / 10.0)
                return when (req.goal) {
                    Goal.TARGETS -> scale?.let { "+${one(d * it)} match" } ?: "+${one(d / base.unit)} rolls"
                    Goal.SPEED -> "+${one(d)} speed"
                    Goal.GEAR_SCORE -> "+${one(d)} score"
                    Goal.DAMAGE -> "+${one(100 * d / kotlin.math.abs(v0))}% damage"
                    Goal.EHP -> "+${one(100 * d / kotlin.math.abs(v0))}% EHP"
                    Goal.CP -> "+${one(100 * d / kotlin.math.abs(v0))}% CP"
                }
            }
            val picks = byHero[h.id].orEmpty()
            val rows = picks.mapNotNull { p ->
                // nothing else moving, or the best build however it is made
                val ranked = if (rebuilds) p.values.indices.map { p.hitIdx.getOrElse(it) { -1 } to p.values[it] } else p.swaps
                val gains = if (ref == null) ranked.map { 1.0 } else ranked.map { (it.second - ref) / base.unit }.filter { it >= minGain }
                if (gains.isEmpty()) return@mapNotNull null
                val kept = ranked.take(gains.size)
                HeroUpgradeRow(p, gains.size, r.sims, label(kept[kept.size / 2].second), label(kept.first().second), gains.sum() / r.sims,
                    kept[kept.size / 2].first, if (rebuilds) p.changes else p.swapChanges)
            }.sortedWith(compareByDescending<HeroUpgradeRow> { it.expected }.thenByDescending { it.hits }.thenBy { it.pick.piece })
            val baseBuild = setup(h.id)?.let { s -> base.items.mapNotNull { box?.itemsById?.get(it) }.takeIf { it.size == 6 }?.let { s.build(it) } }
            HeroUpgradeHero(h, base, rows, if (rebuilds) baseBuild?.let { matchOf(h.id, it)?.pct } else matchFor(h.id)?.pct, picks.count { it.unknown > 0 })
        }.sortedWith(compareByDescending<HeroUpgradeHero> { tier(it.hero.id) }.thenByDescending { it.rows.firstOrNull()?.expected ?: -1.0 }
            .thenBy { heroName(it.hero) })
    }

    /** One piece in the tracker over the game; its advice checks every hero with a setup. */
    fun trackHeroUpgrade(id: Long) {
        val original = box?.itemsById?.get(id)?.takeIf { it.id !in clearedIds && e7.gearscan.data.UpgradeRecord.supported(it) } ?: return
        upgrades.select(original, lastScan)
        configureUpgrade()
        go(Screen.Upgrade)
        openUpgradeOverlay()
    }

    /** These pieces, in order, as the tracker's list over the game. */
    fun trackHeroUpgrades(ids: List<Long>) {
        val b = box ?: return
        val items = ids.distinct().mapNotNull { id ->
            val o = b.itemsById[id]?.takeIf { it.id !in clearedIds && e7.gearscan.data.UpgradeRecord.supported(it) } ?: return@mapNotNull null
            val set = content.set(o.set)
            o to e7.gearscan.overlay.UpgradeTracker.QueueEntry(o.id, null, set?.name ?: o.set.removeSuffix("Set"), set?.icon)
        }
        if (items.isEmpty()) return
        upgrades.startQueue(items, lastScan, source = "heroes")
        configureUpgrade()
        go(Screen.Upgrade)
        openUpgradeOverlay()
    }

    /** Loads one hero's build as the in-game checklist; finishing it records the build. */
    fun loadBuildChecklist(heroId: Long, b: Build, title: String, flag: String? = null) {
        val ob = overlayBuild(heroId, b, title, flag)
        e7.gearscan.overlay.OverlayState.load(ob) { recordBuild(heroId, b, ob.pieces.filter { it.reforgeStep }.map { it.item.id }, e7.gearscan.overlay.OverlayState.rolledMods()) }
    }

    /** Loads the pool's moves as the in-game checklist; finishing it records them all. */
    fun loadPoolChecklist(): Boolean {
        val p = pool ?: return false
        val b = poolOverlay() ?: return false
        e7.gearscan.overlay.OverlayState.load(b) { equipPool(p, b.pieces.filter { it.reforgeStep }.map { it.item.id }, e7.gearscan.overlay.OverlayState.rolledMods()); tab(Screen.Optimize) }
        return true
    }

    fun poolOverlay(): e7.gearscan.overlay.OverlayBuild? {
        val p = pool ?: return null
        val steps = p.builds.flatMap { (id, b) ->
            val name = hero(id)?.let(::heroName) ?: return@flatMap emptyList()
            val ob = b?.let { overlayBuild(id, it, name) } ?: return@flatMap emptyList()
            ob.pieces.filter { !it.onHero || it.otherMenu }.map { it.copyFor(name) }
        }
        val pieces = steps.filter { it.menu == 0 } + steps.filter { it.menu == 1 } + steps.filter { it.menu == 2 }
        return e7.gearscan.overlay.OverlayBuild("Pool · ${pieces.size} moves", null, pieces, emptyList(), p.takeIf { it.fromHistory }?.let { historyFlag(it.startedAt) })
    }

    // ---- over the game ----
    fun historyFlag(at: Long) = "From history · ${e7.gearscan.ui.historyStamp(at)}. Gear may have moved since; check each piece."

    fun overlayBuild(heroId: Long, b: Build, title: String, flag: String? = null): e7.gearscan.overlay.OverlayBuild {
        val h = hero(heroId)
        val reforged = request(heroId).reforged
        val cur = currentBuild(heroId)
        val items = Slot.entries.mapNotNull { s -> b.items.firstOrNull { it.slot == s } }.filter { !it.ghost }
        // a piece the build counts as reforged that isn't yet: equipped as it is, then reforged in its own step
        fun needsReforge(it: Item) = reforged && it.reforgeable && box?.canReforge(it.id) == true
        fun step(it: Item, modStep: Boolean, reforgeStep: Boolean = false): e7.gearscan.overlay.OverlayPiece {
            val shown = reforged && it.reforgeable && (reforgeStep || modStep || !needsReforge(it))
            val main = if (shown) e7.gearscan.calc.Reforge.main(it.mainType, it.mainValue) else it.mainValue
            val holder = it.equippedOn
            val set = content.set(it.set)
            return e7.gearscan.overlay.OverlayPiece(it, set?.icon, "${it.mainType.short} ${it.mainType.format(main)}",
                // the piece as it looks in the game today; a mod is shown as its own step
                (box?.itemsById?.get(it.id) ?: it).let { orig -> orig.subs.indices.map { i -> orig.subs[i].let { s -> (if (s.modified) "改 " else "") + "${s.type.short} ${s.type.format(e7.gearscan.calc.Calc.subValue(orig, i, shown))}" } } },
                when (holder) { null -> "in bag"; heroId -> "on now"; else -> "on " + (hero(holder)?.let(::heroName) ?: "other") },
                holder == heroId,
                it.mod?.takeIf { modStep }?.let { m -> "${m.from.short} ${m.from.format(m.fromValue)} → ${m.to.short} ${m.to.format(m.toValue)}" },
                box?.itemsById?.get(it.id) ?: it, shown, reforgeStep = reforgeStep)
        }
        val pieces = items.map { step(it, false) } + items.filter(::needsReforge).map { step(it, false, true) } + items.filter { it.mod != null }.map { step(it, true) }
        val stats = e7.gearscan.data.Stat.entries.map { st -> Triple(st, b.final[st], cur?.final?.get(st) ?: 0.0) }
        return e7.gearscan.overlay.OverlayBuild(title, h?.let(::face), pieces, stats, flag)
    }

    /** True when the overlay started; false when the permission screen must come first. */
    /** Over the game: false = E7 Gear's own overlay (any phone), true = the phone's floating window showing the app's panel. */
    /** The app runs in the phone's floating (or split) window. */
    var floating by mutableStateOf(false)
    /** In that window with a tracker or checklist loaded: the panel (true) or the whole app. */
    var floatingPanel by mutableStateOf(true)

    /** The loaded tracker or checklist goes to the phone's floating window: the steps once, then the game. */
    private fun nativeOverGame() {
        e7.gearscan.overlay.OverlayService.stop(getApplication())
        e7.gearscan.overlay.OverlayState.expanded = true
        floatingPanel = true
        if (!graph.settings.nativeSeen) { go(Screen.OverlayIntro); return }
        graph.scan.openGame()
    }

    /** Opens the loaded checklist over the game the way the player chose, or the intro when the overlay needs its permission. */
    fun startLoadedOverGame() {
        val app = getApplication<Application>()
        if (graph.settings.overGameNative) { nativeOverGame(); return }
        if (e7.gearscan.overlay.OverlayState.canDraw(app)) { e7.gearscan.overlay.OverlayService.start(app); graph.scan.openGame() } else go(Screen.OverlayIntro)
    }

    /** Closing the panel in the floating window: the tracker or checklist stays saved, the window shows the app. */
    fun closeFloatingPanel() {
        e7.gearscan.overlay.OverlayState.upgrade = null; e7.gearscan.overlay.OverlayState.build = null
        floatingPanel = true
    }

    fun showOverGame(heroId: Long, b: Build, title: String, flag: String? = null): Boolean {
        val app = getApplication<Application>()
        loadBuildChecklist(heroId, b, title, flag)
        if (graph.settings.overGameNative) { nativeOverGame(); return true }
        if (!e7.gearscan.overlay.OverlayState.canDraw(app)) return false
        e7.gearscan.overlay.OverlayService.stop(app)
        e7.gearscan.overlay.OverlayService.start(app)
        graph.settings.overlaySeen = true; save()
        graph.scan.openGame()
        return true
    }

    private fun cancelAllWork() {
        graph.work.cancelAll()
    }

    // ---- persistence ----
    private fun adopted(b: Box?) {
        syncTrackerMarks()
        // results saved before the swap fields existed can't answer "this piece only": they are dropped
        heroUpgrades = runCatching { JSONObject(graph.improveHeroes.result.read().orEmpty()) }.getOrNull()?.takeIf { it.optInt("format") == HERO_UPGRADES_FORMAT }?.let { o -> b?.let { bx -> runCatching {
            HeroUpgradeState(o.getLong("scan"), o.getString("key"), e7.gearscan.opt.HeroUpgrades.Result.of(o.getJSONObject("result"), bx.itemsById),
                running = false, finishedAt = o.getLong("finishedAt"), attempt = o.optInt("attempt"))
        }.getOrNull() } }
        heroUpgradesResumable = runCatching { JSONObject(graph.improveHeroes.progress.read().orEmpty()).let { !it.getBoolean("finished") && it.getString("light") == heroUpgradeKey() } }
            .getOrDefault(false)
    }

    private fun syncTrackerMarks() {
        syncClearedUpgradeQueue()
        upgrades.canUndoSold=graph.marks.sold.any { mark->upgrades.queue.any { it.id==mark.id } }
    }

    fun save() { graph.store.saveNow() }

    private fun saveBox() = graph.scan.saveBox()

    // last in the class: startup work started here reads fields declared above, which must exist before it runs
    init {
        e7.gearscan.data.Http.agent = "E7Gear/${graph.appUpdate.version}"
        graph.scan.detectGame()
        graph.scan.host = object : ScanFeature.Host {
            override fun adopted(box: Box?) = this@AppModel.adopted(box)
            override fun restored() {
                upgrades.restore(runCatching { JSONObject(graph.tracker.upgrades.read().orEmpty()) }.getOrDefault(JSONObject()))
                syncTrackerMarks()
            }
            override fun erased() {
                e7.gearscan.overlay.OverlayState.upgrade = null
                upgrades.restore(JSONObject(), write = false)
            }
        }
        e7.gearscan.overlay.OverlayState.progressFile = graph.tracker.checklist.file
        upgrades.cancelCheck = { graph.tracker.check.cancel() }
        upgrades.requestCheck = ::checkUpgrade
        upgrades.canReforge = { id -> box?.canReforge(id) == true }
        upgrades.equipOn = ::equipTracked
        upgrades.switchTo = { id -> selectUpgrade(id); checkUpgrade() }
        upgrades.rebuilds = heroUpgradeRebuilds
        upgrades.setRebuilds = { on -> useHeroUpgradeRebuilds(on); upgrades.rebuilds = on; checkUpgrade() }
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { graph.loadOnce(); graph.scan.readBox() }
            graph.feedback.loadCrash()
            graph.scan.adopt(b)
            stack = listOf(if (!graph.settings.consent && box == null) Screen.ScanIntro else Screen.Optimize)
            ready = true
            graph.heroes.team.firstOrNull()?.let { graph.heroes.ensurePresets(it) }
            if (content.heroes.isEmpty()) graph.content.checkNow()
            else if (System.currentTimeMillis() - graph.content.checkedAt > 24 * 3600 * 1000L) graph.content.checkNow(quiet = true)
            graph.appUpdate.checkNow()
            graph.appUpdate.cleanOld()
            graph.feedback.flushQueued()
        }
        viewModelScope.launch { graph.scan.watch() }
    }
}
