package e7.gearscan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.data.Box
import e7.gearscan.data.Http
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.feedback.FeedbackFeature
import e7.gearscan.features.findgear.FindGearFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.history.HistoryFeature
import e7.gearscan.features.improveheroes.ImproveHeroesFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.optimize.OptimizeFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.sellreview.SellReviewFeature
import e7.gearscan.features.settings.SettingsFeature
import e7.gearscan.features.tracker.ChecklistFeature
import e7.gearscan.features.tracker.OverGame
import e7.gearscan.features.tracker.OverGameLauncher
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.features.update.AppUpdateFeature
import e7.gearscan.overlay.OverlayWindow
import e7.gearscan.state.BoxEvents
import e7.gearscan.state.Disk
import e7.gearscan.state.FeatureContext
import e7.gearscan.state.ForegroundHost
import e7.gearscan.state.Migrations
import e7.gearscan.state.Nav
import e7.gearscan.state.NoForeground
import e7.gearscan.state.NoPlatform
import e7.gearscan.state.Platform
import e7.gearscan.state.Revision
import e7.gearscan.state.Store
import e7.gearscan.state.WorkHost
import e7.gearscan.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val STATE_VERSION = 2

class AppGraph(val disk: Disk, val scope: CoroutineScope, foreground: ForegroundHost = NoForeground, val platform: Platform = NoPlatform) : BoxEvents {
    val store = Store(disk, version = STATE_VERSION, migrations = Migrations.all, scope = scope, fileName = "state.json")
    val http = Http("E7Gear/${platform.appVersion}")
    val rev = Revision { store.saveSoon() }
    val work = WorkHost(scope, foreground)
    val messages = Messages()
    val nav: Nav = Nav(Screen.Optimize, { id -> heroes.heroName(id) }, { pool.state?.running == true }, messages::dismissConfirm)
    val overlay = OverlayWindow()
    private val ctx = FeatureContext(disk, scope, platform, store, http, rev, work, messages, nav, overlay)
    val settings = SettingsFeature(ctx)
    val appUpdate = AppUpdateFeature(ctx)
    val content = ContentFeature(ctx)
    val feedback = FeedbackFeature(ctx, content)
    val scan = ScanFeature(ctx, settings, feedback, this)
    val marks = MarksFeature(ctx, scan)
    val records = RecordsFeature(ctx, scan)
    val heroes = HeroesFeature(ctx, content, scan)
    val history = HistoryFeature(ctx, scan, heroes)
    val optimize = OptimizeFeature(ctx, settings, feedback, heroes, history)
    val pool = PoolFeature(ctx, feedback, scan, heroes, history)
    val sellReview = SellReviewFeature(ctx, content, scan, marks, records, heroes, pool)
    val improveHeroes = ImproveHeroesFeature(ctx, content, scan, marks, records, heroes, pool)
    val findGear = FindGearFeature(ctx, content, scan, marks, records)
    private val launcher = OverGameLauncher(ctx, settings, scan)
    val tracker = TrackerFeature(ctx, content, scan, marks, records, heroes, pool, sellReview, improveHeroes, launcher)
    val checklist = ChecklistFeature(ctx, content, scan, heroes, history, pool, tracker)
    val overGame = OverGame(ctx, launcher, tracker, checklist)
    val features = listOf(content, appUpdate, scan, heroes, marks, records, optimize, pool, history, sellReview, improveHeroes, findGear, tracker, checklist, settings, feedback)
    private var loaded = false

    var ready by mutableStateOf(false)
        private set

    override fun boxReplacing() = features.forEach { it.onBoxReplacing() }

    override fun boxChanged(old: Box?, new: Box?) {
        features.forEach { it.onBoxChanged(old, new) }
    }

    override fun boxAdopted(box: Box?) = features.forEach { it.onBoxAdopted(box) }

    override fun imported() = features.forEach { it.onImported() }

    override fun restored() = features.forEach { it.onRestored() }

    override fun erased() = features.forEach { it.onErased() }

    fun loadOnce(): Boolean {
        if (loaded) return false
        loaded = true
        store.load()
        disk.delete("cleanup_progress.json")
        return true
    }

    /** Loads the saved state and the box, shows the first screen, and starts the daily checks; the job ends the scan watcher. */
    fun start(): Job {
        ready = false
        scan.detectGame()
        return scope.launch {
            launch { scan.watch() }
            val b = withContext(Dispatchers.IO) { loadOnce(); scan.readBox() }
            feedback.loadCrash()
            scan.adopt(b)
            nav.stack = listOf(if (!settings.consent && scan.box == null) Screen.ScanIntro else Screen.Optimize)
            ready = true
            heroes.team.firstOrNull()?.let { heroes.ensurePresets(it) }
            if (content.content.heroes.isEmpty()) {
                content.checkNow()
            } else if (System.currentTimeMillis() - content.checkedAt > 24 * 3600 * 1000L) {
                content.checkNow(quiet = true)
            }
            appUpdate.checkNow()
            appUpdate.cleanOld()
            feedback.flushQueued()
        }
    }
}
