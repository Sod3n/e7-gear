package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Browsing ranges, not a text field: never starts an input connection or opens a keyboard. */
fun nameMatches(name: String, group: String): Boolean = when (group) {
    "" -> true
    "A-F" -> name.firstOrNull()?.uppercaseChar() in 'A'..'F'
    "G-L" -> name.firstOrNull()?.uppercaseChar() in 'G'..'L'
    "M-R" -> name.firstOrNull()?.uppercaseChar() in 'M'..'R'
    "S-Z" -> name.firstOrNull()?.uppercaseChar() in 'S'..'Z'
    "Other" -> name.firstOrNull()?.uppercaseChar() !in 'A'..'Z'
    else -> name.contains(group.trim(), true) // preserved filters from older local state
}

@Composable
fun NameFilter(value: String, hint: String = "Name", onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowCard(hint, { open = !open }) { Text(value.ifEmpty { "All names" }, style = zen(12.sp)) }
        if (open) CardGrid(listOf("All", "A-F", "G-L", "M-R", "S-Z", "Other"), columns = 3, gap = 6.dp) { label, m ->
            val group = if (label == "All") "" else label
            Pill(label, modifier = m, selected = value == group, onClick = { onChange(group); open = false })
        }
    }
}

/** Free text arrives only from an explicit Paste action, so none of these controls summon an IME. */
@Composable
@OptIn(LinkStyle::class)
fun PasteField(value: String, hint: String, limit: Int, multiline: Boolean = false, onChange: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(C.field).border(2.dp, C.edge, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(hint, style = zen(11.sp, FontWeight.Medium, C.muted))
        if (value.isNotEmpty()) Text(value, style = zen(13.sp, FontWeight.Medium), maxLines = if (multiline) 6 else 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkText("Paste") { clipboard.getText()?.text?.let { onChange(it.take(limit)) } }
            if (value.isNotEmpty()) LinkText("Clear") { onChange("") }
            if (multiline) Text("${value.length} / $limit", style = zen(10.sp, FontWeight.Medium, C.muted))
        }
    }
}
