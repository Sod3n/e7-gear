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
