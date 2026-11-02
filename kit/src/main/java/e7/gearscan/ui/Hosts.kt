package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle

/** Asks before a reset; Cancel, back or a tap outside keeps everything as it is. */
@Composable
fun ConfirmHost(messages: Messages) {
    val c = messages.dialog ?: return
    Box(Modifier.fillMaxSize().background(C.scrim).tap { messages.dialog = null }, contentAlignment = Alignment.Center) {
        Card(Modifier.padding(horizontal = 28.dp).tap { }, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Text(c.title, style = dela(18.sp))
            VSpace(6.dp); Text(c.body, style = zen(13.sp, color = C.muted))
            VSpace(14.dp)
            val run = c.run
            if (run == null) PinkButton("Got it", Modifier.fillMaxWidth()) { messages.dialog = null }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Cancel", Modifier.weight(1f), height = 46.dp) { messages.dialog = null }
                PinkButton(c.action ?: "Reset", Modifier.weight(1f)) { messages.dialog = null; c.run?.invoke() }
            }
        }
    }
}

/** Floats over the bottom of the screen, so showing it never moves anything. */
@Composable
fun ToastHost(messages: Messages, modifier: Modifier) {
    val t = messages.toast ?: return
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // counts only while the app is in front: a toast set from over the game waits for the player to come back
    LaunchedEffect(t.id) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { kotlinx.coroutines.delay(6000); if (messages.toast?.id == t.id) messages.toast = null }
    }
    Row(modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp).fillMaxWidth()
        .clip(RoundedCornerShape(14.dp)).background(C.ink).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t.text, style = zen(13.sp, color = C.card), modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (t.action != null) Text(t.action, style = dela(13.sp, C.pink), modifier = Modifier.tap { t.onAction?.invoke(); messages.toast = null })
    }
}
