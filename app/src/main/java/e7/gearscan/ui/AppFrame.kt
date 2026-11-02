package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import e7.gearscan.AppModel
import e7.gearscan.Screen

@Composable
fun Frame(
    model: AppModel?,
    tab: Screen?,
    scroll: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Frame(scroll = scroll, actions = actions, tabBar = if (tab != null && model != null) ({ TabBar(model, tab) }) else null, content = content)
}

@Composable
fun TabBar(model: AppModel, current: Screen) {
    Column(Modifier.fillMaxWidth().background(C.card).drawBehind {
        drawRect(C.edge, size = androidx.compose.ui.geometry.Size(size.width, 2.5.dp.toPx()))
    }.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, s) in listOf("Optimize" to Screen.Optimize, "Heroes" to Screen.Heroes, "Gear" to Screen.Gear)) {
                val on = s == current
                val shape = RoundedCornerShape(999.dp)
                Box(Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.hardShadow(C.shadow, 2.dp, 2.dp, 20.dp).clip(shape).background(C.pink).border(2.dp, C.edge, shape) else Modifier.clip(shape))
                    .clickable { model.graph.nav.tab(s) },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = zen(14.sp, color = if (on) C.onAccent else C.muted))
                }
            }
            ThemeSwitch()
        }
    }
}

@Composable
fun ArtifactIcon(model: AppModel, art: e7.gearscan.data.Artifact?, size: androidx.compose.ui.unit.Dp) {
    ArtifactIcon(art, size) { model.graph.content.content.artifactIcon(it) }
}

/** Shown after the app crashed: what happened, with a one-tap report. */
@Composable
fun CrashCard(model: e7.gearscan.AppModel) {
    val crash = model.graph.feedback.lastCrash ?: return
    Card(shadow = null, radius = 14.dp, border = 2.dp) {
        Text("E7 Gear closed unexpectedly last time", style = dela(14.sp))
        VSpace(4.dp)
        Text(crash.lineSequence().first(), style = zen(12.sp, FontWeight.Medium, C.muted), maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkText("Report this") { model.graph.nav.go(e7.gearscan.Screen.Feedback(crash)); model.graph.feedback.dismissCrash() }
            LinkText("Dismiss", color = C.muted) { model.graph.feedback.dismissCrash() }
        }
    }
}
