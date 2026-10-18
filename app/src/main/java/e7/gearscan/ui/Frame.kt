package e7.gearscan.ui

import androidx.compose.foundation.background
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
        drawRect(C.ink, size = androidx.compose.ui.geometry.Size(size.width, 2.5.dp.toPx()))
    }.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, s) in listOf("Optimize" to Screen.Optimize, "Heroes" to Screen.Heroes)) {
                val on = s == current
                val shape = RoundedCornerShape(999.dp)
                Box(Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.hardShadow(C.ink, 2.dp, 2.dp, 20.dp).clip(shape).background(C.pink).border(2.dp, C.ink, shape) else Modifier.clip(shape))
                    .clickable { model.tab(s) },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = zen(14.sp, color = if (on) C.ink else C.muted))
                }
            }
        }
    }
}
