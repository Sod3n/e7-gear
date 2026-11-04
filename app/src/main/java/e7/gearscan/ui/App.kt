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
import e7.gearscan.AppGraph
import e7.gearscan.features.sellreview.SellFiltersScreen
import e7.gearscan.features.sellreview.SellKeptScreen
import e7.gearscan.Screen

@Composable
fun App(graph: AppGraph, onExit: () -> Unit) {
    val back = remember(graph) {
        object : BackLink {
            override val label get() = graph.nav.backLabel()
            override fun go() { graph.nav.back() }
        }
    }
    CompositionLocalProvider(LocalBack provides back, LocalImageBase provides graph.content.imageBase, LocalWearerFace provides graph.heroes::faceOf) {
        AppContent(graph, onExit)
    }
}

@Composable
private fun AppContent(graph: AppGraph, onExit: () -> Unit) {
    BackHandler { if (!graph.nav.back()) onExit() }
    if (!graph.ready) { Box(Modifier.fillMaxSize().background(C.paper)); return }
    if (graph.content.content.heroes.isEmpty()) { GameDataScreen(graph); return }
    // in the phone's floating window, a loaded tracker or checklist is all the window shows
    val window = graph.overlay
    val loaded = graph.tracker.shown || graph.checklist.build != null
    if (window.floating && window.floatingPanel && loaded) { FloatingPanel(graph); return }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
    // the whole app in the floating window: a slim bar above it goes back to the panel, covering nothing
    if (window.floating && loaded) Row(Modifier.fillMaxWidth().background(C.pink).tap { window.floatingPanel = true }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text("‹ Back to the " + if (graph.tracker.shown) "tracker" else "checklist", style = zen(13.sp, color = C.onAccent))
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        val nav = graph.nav
        val entry = nav.entries.last()
        val saveable = rememberSaveableStateHolder()
        val known = remember { HashSet<Long>() }
        SideEffect {
            val live = nav.entries.map { it.id }.toSet()
            (known - live).forEach { saveable.removeState(it) }
            known.clear(); known.addAll(live)
        }
        saveable.SaveableStateProvider(entry.id) {
        when (val s = entry.screen) {
            Screen.ScanIntro -> ScanIntroScreen(graph)
            Screen.ScanData -> ScanDataScreen(graph)
            Screen.Capture -> CaptureScreen(graph)
            Screen.ScanResult -> ScanResultScreen(graph)
            Screen.Optimize -> OptimizeScreen(graph)
            Screen.Heroes -> HeroesScreen(graph)
            Screen.Gear -> GearScreen(graph)
            Screen.Results -> ResultsScreen(graph)
            Screen.GhostBuild -> GhostBuildScreen(graph)
            is Screen.Compare -> CompareScreen(graph, s.index, s.poolHero)
            is Screen.HeroDetail -> HeroDetailScreen(graph, s.id)
            is Screen.DiffItems -> DiffItemsScreen(graph, s.kind)
            Screen.OverlayIntro -> OverlayIntroScreen(graph)
            is Screen.Feedback -> FeedbackScreen(graph, s.error)
            Screen.PoolRun -> PoolRunScreen(graph)
            Screen.Upgrade -> UpgradeScreen(graph)
            Screen.GearPotential -> FindGearScreen(graph)
            Screen.SellFilters -> SellFiltersScreen(graph)
            Screen.SellKept -> SellKeptScreen(graph)
            is Screen.HeroUpgrades -> HeroUpgradesScreen(graph, s.heroId)
            is Screen.PoolHero -> PoolHeroScreen(graph, s.id, s.setup)
            is Screen.PoolReview -> CompareScreen(graph, 0, s.id, review = true)
        }
        }
        SheetHost(graph)
        ToastHost(graph.messages, Modifier.align(Alignment.BottomCenter))
        ConfirmHost(graph.messages)
    }
    }
}
