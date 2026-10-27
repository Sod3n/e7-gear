package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import e7.gearscan.AppModel
import e7.gearscan.overlay.OverlayState
import e7.gearscan.overlay.OverlayUi

/**
 * The tracker or checklist as the whole content of the phone's floating window (Honor and other freeform windows). The
 * system moves, resizes and minimizes the window; collapsing here shows the whole app instead, closing ends the panel.
 */
@Composable
fun FloatingPanel(model: AppModel) {
    DisposableEffect(Unit) {
        OverlayState.native = true; OverlayState.expanded = true
        onDispose { OverlayState.native = false }
    }
    Box(Modifier.fillMaxSize().background(C.paper).padding(6.dp), contentAlignment = Alignment.TopCenter) {
        OverlayUi({ _, _ -> }, { expanded -> if (!expanded) { OverlayState.expanded = true; model.floatingPanel = false } }) { model.closeFloatingPanel() }
    }
}
