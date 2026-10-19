package e7.gearscan.ui

import androidx.activity.compose.BackHandler
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
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
import e7.gearscan.Screen

@Composable
fun App(model: AppModel, onExit: () -> Unit) {
    BackHandler { if (!model.back()) onExit() }
    if (!model.ready) { Box(Modifier.fillMaxSize().background(C.paper)); return }
    if (model.content.heroes.isEmpty()) { GameDataScreen(model); return }
    Box(Modifier.fillMaxSize()) {
        when (val s = model.screen) {
            Screen.ScanIntro -> ScanIntroScreen(model)
            Screen.ScanData -> ScanDataScreen(model)
            Screen.Capture -> CaptureScreen(model)
            Screen.ScanResult -> ScanResultScreen(model)
            Screen.Optimize -> OptimizeScreen(model)
            Screen.Heroes -> HeroesScreen(model)
            Screen.Results -> ResultsScreen(model)
            Screen.GhostBuild -> GhostBuildScreen(model)
            is Screen.Compare -> CompareScreen(model, s.index, s.poolHero)
            is Screen.HeroDetail -> HeroDetailScreen(model, s.id)
            is Screen.DiffItems -> DiffItemsScreen(model, s.kind)
            Screen.OverlayIntro -> OverlayIntroScreen(model)
            is Screen.Feedback -> FeedbackScreen(model, s.error)
            Screen.PoolRun -> PoolRunScreen(model)
            is Screen.PoolHero -> PoolHeroScreen(model, s.id, s.setup)
            is Screen.PoolReview -> CompareScreen(model, 0, s.id, review = true)
        }
        SheetHost(model)
        ToastHost(model, Modifier.align(Alignment.BottomCenter))
    }
}

/** Floats over the bottom of the screen, so showing it never moves anything. */
@Composable
private fun ToastHost(model: AppModel, modifier: Modifier) {
    val t = model.toast ?: return
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // counts only while the app is in front: a toast set from over the game waits for the player to come back
    LaunchedEffect(t.id) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { kotlinx.coroutines.delay(6000); if (model.toast?.id == t.id) model.toast = null }
    }
    Row(modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp).fillMaxWidth()
        .clip(RoundedCornerShape(14.dp)).background(C.ink).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t.text, style = zen(13.sp, color = C.card), modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (t.action != null) Text(t.action, style = dela(13.sp, C.pink), modifier = Modifier.tap { t.onAction?.invoke(); model.toast = null })
    }
}
