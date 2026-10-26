package e7.gearscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Screen

/**
 * Screen layout from the design: scrolling content with an 18 dp gutter and 11 dp gaps, an
 * optional fixed action row, and the two-tab bar on the main screens.
 */
@Composable
fun Frame(
    model: AppModel?,
    tab: Screen?,
    scroll: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PaperBrush).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content,
        )
        if (actions != null) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp)
                .then(if (tab == null) Modifier.navigationBarsPadding() else Modifier),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (tab != null && model != null) TabBar(model, tab)
    }
}

@Composable
fun TabBar(model: AppModel, current: Screen) {
    Column(Modifier.fillMaxWidth().background(C.card).drawBehind {
        drawRect(C.edge, size = androidx.compose.ui.geometry.Size(size.width, 2.5.dp.toPx()))
    }.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, s) in listOf("Optimize" to Screen.Optimize, "Heroes" to Screen.Heroes)) {
                val on = s == current
                val shape = RoundedCornerShape(999.dp)
                Box(Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.hardShadow(C.shadow, 2.dp, 2.dp, 20.dp).clip(shape).background(C.pink).border(2.dp, C.edge, shape) else Modifier.clip(shape))
                    .clickable { model.tab(s) },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = zen(14.sp, color = if (on) C.onAccent else C.muted))
                }
            }
            ThemeSwitch()
        }
    }
}

/** Moon in the light theme, sun in the dark one. The phone's setting decides until the first tap. */
@Composable
fun ThemeSwitch() {
    val ctx = LocalContext.current
    val dark = Themes.dark
    Box(Modifier.size(40.dp).clip(CircleShape).background(C.card).border(2.dp, C.edge, CircleShape)
        .clickable { Themes.set(ctx, !dark) }
        .semantics { contentDescription = if (dark) "Light theme" else "Dark theme" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2
            if (dark) {
                drawCircle(C.ink, r * 0.45f)
                for (k in 0 until 8) {
                    val ray = Offset(cos(k * PI / 4).toFloat(), sin(k * PI / 4).toFloat())
                    drawLine(C.ink, center + ray * (r * 0.68f), center + ray * r, r * 0.16f, StrokeCap.Round)
                }
            } else {
                drawCircle(C.ink, r * 0.82f)
                drawCircle(C.card, r * 0.7f, center + Offset(r * 0.42f, -r * 0.32f))
            }
        }
    }
}
