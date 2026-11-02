package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SheetBody(title: String, trailing: @Composable (() -> Unit)? = null, button: String? = null, onButton: (() -> Unit)? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TitleStyle, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
        if (button != null && onButton != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { PinkButton(button, Modifier.fillMaxWidth(), onClick = onButton) }
        }
    }
}

/** A section that opens in place: a full-width row with a chevron, never a link. */
@Composable
fun Fold(title: String, open: Boolean, toggle: () -> Unit, content: @Composable () -> Unit) {
    Card(shadowOffset = 3.dp, radius = 14.dp, border = 2.dp, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().tap(toggle).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = zen(13.sp), modifier = Modifier.weight(1f))
            Text("›", style = zen(16.sp), modifier = Modifier.rotate(if (open) -90f else 90f))
        }
        if (open) Column(Modifier.fillMaxWidth().background(C.field).border(1.dp, C.line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
