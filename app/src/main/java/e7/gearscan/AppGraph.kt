package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Mirror
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.feedback.FeedbackFeature
import e7.gearscan.features.findgear.FindGearFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.history.HistoryFeature
import e7.gearscan.features.improveheroes.ImproveHeroesFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.optimize.OptimizeFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.sellreview.SellReviewFeature
import e7.gearscan.features.settings.SettingsFeature
import e7.gearscan.features.tracker.ChecklistFeature
import e7.gearscan.features.tracker.OverGame
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.features.update.AppUpdateFeature
import e7.gearscan.overlay.OverlayWindow
import e7.gearscan.state.Disk
import e7.gearscan.state.ForegroundHost
import e7.gearscan.state.Migrations
import e7.gearscan.state.Nav
import e7.gearscan.state.NoForeground
import e7.gearscan.state.NoPlatform
import e7.gearscan.state.Platform
import e7.gearscan.state.Store
import e7.gearscan.state.WorkHost
import e7.gearscan.ui.Messages
import kotlinx.coroutines.CoroutineScope

const val STATE_VERSION = 2

class AppGraph(val disk: Disk, val scope: CoroutineScope, foreground: ForegroundHost = NoForeground, val platform: Platform = NoPlatform) {
    val store = Store(disk, version = STATE_VERSION, migrations = Migrations.all, scope = scope, fileName = "state.json")
    val work = WorkHost(scope, foreground)
    val messages = Messages()
    val nav = Nav(Screen.Optimize, { id -> heroes.heroName(id) }, { pool.state?.running == true }, messages::dismissConfirm)
    val content = ContentFeature(this)
    val appUpdate = AppUpdateFeature(this)
    val scan = ScanFeature(this)
    val heroes = HeroesFeature(this)
    val marks = MarksFeature(this)
    val optimize = OptimizeFeature(this)
    val pool = PoolFeature(this)
    val history = HistoryFeature(this)
    val sellReview = SellReviewFeature(this)
    val improveHeroes = ImproveHeroesFeature(this)
    val findGear = FindGearFeature(this)
    val overlay = OverlayWindow()
    val overGame = OverGame(this)
    val tracker = TrackerFeature(this)
    val checklist = ChecklistFeature(this)
    val settings = SettingsFeature(this)
    val feedback = FeedbackFeature(this)
    val features = listOf(content, appUpdate, scan, heroes, marks, optimize, pool, history, sellReview, improveHeroes, findGear, tracker, checklist, settings, feedback)
    private var loaded = false

    init {
        store.beforeSave = { if (content.mirror != Mirror.active) content.mirror = Mirror.active }
        store.afterLoad = { Mirror.active = content.mirror }
    }

    fun boxChanged(old: Box?, new: Box?) {
        features.forEach { it.onBoxChanged(old, new) }
    }

    fun loadOnce(): Boolean {
        if (loaded) return false
        loaded = true
        store.load()
        disk.delete("cleanup_progress.json")
        return true
    }
}
