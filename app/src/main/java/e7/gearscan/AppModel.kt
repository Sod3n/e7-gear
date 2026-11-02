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

class AppModel(app: Application) : AndroidViewModel(app) {
    val graph = getApplication<E7GearApp>().graph
    private val files = app.filesDir

    val content get() = graph.content.content
    val box get() = graph.scan.box
    val rev get() = graph.scan.rev
    private val configs get() = graph.heroes.configs
    val lastScan get() = graph.scan.lastScan
    var ready by mutableStateOf(false)
        private set

    private fun hero(id: Long): OwnedHero? = graph.heroes.hero(id)
    private fun heroName(h: OwnedHero) = graph.heroes.heroName(h)
    private fun face(h: OwnedHero) = graph.heroes.face(h)

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
    private fun begin(out: Outcome) = graph.messages.show(out)
    private fun setup(id: Long, excluded: Set<Long> = emptySet(), canTakeFrom: (Long) -> Boolean = { tier(it) < tier(id) }) =
        graph.heroes.setup(id, excluded, canTakeFrom)
    private fun recordBuild(heroId: Long, b: Build, reforge: List<Long>, mods: List<Pair<Long, e7.gearscan.data.Mod>>?) =
        graph.heroes.recordBuild(heroId, b, reforge, mods)
    private fun equipPool(run: e7.gearscan.features.pool.PoolState?, reforge: List<Long>, mods: List<Pair<Long, e7.gearscan.data.Mod>>?) =
        graph.pool.equip(run, reforge, mods)

    // swiped away from recents: nothing is left to show the result, so stop rather than leave the service running
    override fun onCleared() {
        cancelAllWork()
        super.onCleared()
    }

    /** Loads one hero's build as the in-game checklist; finishing it records the build. */
    fun loadBuildChecklist(heroId: Long, b: Build, title: String, flag: String? = null) {
        val ob = overlayBuild(heroId, b, title, flag)
        graph.checklist.load(ob) { recordBuild(heroId, b, ob.pieces.filter { it.reforgeStep }.map { it.item.id }, graph.checklist.rolledMods()) }
    }

    /** Loads the pool's moves as the in-game checklist; finishing it records them all. */
    fun loadPoolChecklist(): Boolean {
        val p = pool ?: return false
        val b = poolOverlay() ?: return false
        graph.checklist.load(b) { equipPool(p, b.pieces.filter { it.reforgeStep }.map { it.item.id }, graph.checklist.rolledMods()); graph.nav.tab(Screen.Optimize) }
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
        return e7.gearscan.overlay.OverlayBuild("Pool · ${pieces.size} moves", null, pieces, emptyList(), p.takeIf { it.fromHistory }?.let { graph.history.flag(it.startedAt) })
    }

    // ---- over the game ----

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

    /** Opens the loaded checklist over the game the way the player chose, or the intro when the overlay needs its permission. */
    fun startLoadedOverGame() = graph.overGame.open()

    fun showOverGame(heroId: Long, b: Build, title: String, flag: String? = null): Boolean {
        loadBuildChecklist(heroId, b, title, flag)
        return graph.overGame.showChecklist()
    }

    private fun cancelAllWork() {
        graph.work.cancelAll()
    }

    // last in the class: startup work started here reads fields declared above, which must exist before it runs
    init {
        e7.gearscan.data.Http.agent = "E7Gear/${graph.appUpdate.version}"
        graph.scan.detectGame()
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { graph.loadOnce(); graph.scan.readBox() }
            graph.feedback.loadCrash()
            graph.scan.adopt(b)
            graph.nav.stack = listOf(if (!graph.settings.consent && box == null) Screen.ScanIntro else Screen.Optimize)
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
