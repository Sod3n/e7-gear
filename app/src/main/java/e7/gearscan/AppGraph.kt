package e7.gearscan

import e7.gearscan.data.Mirror
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.findgear.FindGearFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.history.HistoryFeature
import e7.gearscan.features.improveheroes.ImproveHeroesFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.sellreview.SellReviewFeature
import e7.gearscan.features.settings.SettingsFeature
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.state.Disk
import e7.gearscan.state.Migrations
import e7.gearscan.state.Nav
import e7.gearscan.state.Store
import e7.gearscan.state.WorkHost
import e7.gearscan.ui.Messages
import kotlinx.coroutines.CoroutineScope

const val STATE_VERSION = 2

class AppGraph(val disk: Disk, val scope: CoroutineScope) {
    val store = Store(disk, version = STATE_VERSION, migrations = Migrations.all, scope = scope, fileName = "state.json")
    val work = WorkHost(scope)
    val messages = Messages()
    val nav = Nav(Screen.Optimize, messages::dismissConfirm)
    val content = ContentFeature(this)
    val scan = ScanFeature(this)
    val heroes = HeroesFeature(this)
    val marks = MarksFeature(this)
    val pool = PoolFeature(this)
    val history = HistoryFeature(this)
    val sellReview = SellReviewFeature(this)
    val improveHeroes = ImproveHeroesFeature(this)
    val findGear = FindGearFeature(this)
    val tracker = TrackerFeature(this)
    val settings = SettingsFeature(this)
    private var loaded = false

    init {
        store.beforeSave = { if (content.mirror != Mirror.active) content.mirror = Mirror.active }
        store.afterLoad = { Mirror.active = content.mirror }
    }

    fun loadOnce(): Boolean {
        if (loaded) return false
        loaded = true
        store.load()
        return true
    }
}
