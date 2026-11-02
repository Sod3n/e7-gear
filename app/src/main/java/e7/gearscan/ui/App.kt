package e7.gearscan.ui

import androidx.activity.compose.BackHandler
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import e7.gearscan.AppModel
import e7.gearscan.features.sellreview.SellFiltersScreen
import e7.gearscan.features.sellreview.SellKeptScreen
import e7.gearscan.Screen

@Composable
fun App(model: AppModel, onExit: () -> Unit) {
    val back = remember(model) {
        object : BackLink {
            override val label get() = model.backLabel()
            override fun go() { model.back() }
        }
    }
    CompositionLocalProvider(LocalBack provides back, LocalImageBase provides model.graph.content.imageBase) { AppContent(model, onExit) }
}

@Composable
private fun AppContent(model: AppModel, onExit: () -> Unit) {
    BackHandler { if (!model.back()) onExit() }
    if (!model.ready) { Box(Modifier.fillMaxSize().background(C.paper)); return }
    if (model.graph.content.content.heroes.isEmpty()) { GameDataScreen(model); return }
    // in the phone's floating window, a loaded tracker or checklist is all the window shows
    val window = model.graph.overlay
    val loaded = model.graph.tracker.shown || model.graph.checklist.build != null
    if (window.floating && window.floatingPanel && loaded) { FloatingPanel(model); return }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
    // the whole app in the floating window: a slim bar above it goes back to the panel, covering nothing
    if (window.floating && loaded) Row(Modifier.fillMaxWidth().background(C.pink).tap { window.floatingPanel = true }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text("‹ Back to the " + if (model.graph.tracker.shown) "tracker" else "checklist", style = zen(13.sp, color = C.onAccent))
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        val nav = model.graph.nav
        val entry = nav.stack.last()
        val saveable = rememberSaveableStateHolder()
        val known = remember { HashSet<Long>() }
        SideEffect {
            val live = nav.stack.map { it.id }.toSet()
            (known - live).forEach { saveable.removeState(it) }
            known.clear(); known.addAll(live)
        }
        saveable.SaveableStateProvider(entry.id) {
        when (val s = entry.screen) {
            Screen.ScanIntro -> ScanIntroScreen(model)
            Screen.ScanData -> ScanDataScreen(model)
            Screen.Capture -> CaptureScreen(model)
            Screen.ScanResult -> ScanResultScreen(model)
            Screen.Optimize -> OptimizeScreen(model)
            Screen.Heroes -> HeroesScreen(model)
            Screen.Gear -> GearScreen(model)
            Screen.Results -> ResultsScreen(model)
            Screen.GhostBuild -> GhostBuildScreen(model)
            is Screen.Compare -> CompareScreen(model, s.index, s.poolHero)
            is Screen.HeroDetail -> HeroDetailScreen(model, s.id)
            is Screen.DiffItems -> DiffItemsScreen(model, s.kind)
            Screen.OverlayIntro -> OverlayIntroScreen(model)
            is Screen.Feedback -> FeedbackScreen(model, s.error)
            Screen.PoolRun -> PoolRunScreen(model)
            Screen.Upgrade -> UpgradeScreen(model)
            Screen.GearPotential -> FindGearScreen(model)
            Screen.SellFilters -> SellFiltersScreen(model)
            Screen.SellKept -> SellKeptScreen(model)
            is Screen.HeroUpgrades -> HeroUpgradesScreen(model, s.heroId)
            is Screen.PoolHero -> PoolHeroScreen(model, s.id, s.setup)
            is Screen.PoolReview -> CompareScreen(model, 0, s.id, review = true)
        }
        }
        SheetHost(model)
        ToastHost(model.graph.messages, Modifier.align(Alignment.BottomCenter))
        ConfirmHost(model.graph.messages)
    }
    }
}
