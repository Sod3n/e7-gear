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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

const val NAME_SEARCH_TAG = "name-search"

// the old letter groups, still in some saved filters: they now mean no filter
private val LETTER_GROUPS = setOf("A-F", "G-L", "M-R", "S-Z", "Other")

fun nameMatches(name: String, query: String): Boolean =
    query.isBlank() || query in LETTER_GROUPS || name.contains(query.trim(), ignoreCase = true)

/** A name search: finding a hero or set by name is where typing is the point (owner, 2026-11-04). */
@KeyboardInput
@Composable
fun NameFilter(value: String, hint: String = "Name", onChange: (String) -> Unit) {
    LaunchedEffect(value) { if (value in LETTER_GROUPS) onChange("") }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(C.field).border(2.dp, C.edge, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchGlyph()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search by ${hint.lowercase()}", style = zen(13.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(value, { onChange(it.take(40)) }, singleLine = true,
                textStyle = zen(13.sp, FontWeight.Medium, C.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(C.ink),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search, autoCorrect = false),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().described(hint).testTag(NAME_SEARCH_TAG))
        }
        if (value.isNotEmpty()) Box(Modifier.size(24.dp).tap { onChange(""); focus.clearFocus() }.described("Clear $hint"), contentAlignment = Alignment.Center) { ClearGlyph() }
    }
}

private fun Modifier.described(label: String) = this.then(androidx.compose.ui.Modifier.semantics { contentDescription = label })

@Composable
private fun SearchGlyph() = androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
    val w = 2.dp.toPx()
    val r = size.minDimension * 0.34f
    val c = androidx.compose.ui.geometry.Offset(r + w / 2, r + w / 2)
    drawCircle(C.muted, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    drawLine(C.muted, androidx.compose.ui.geometry.Offset(c.x + r * 0.72f, c.y + r * 0.72f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height - w / 2), w, androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
private fun ClearGlyph() = androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
    val w = 2.dp.toPx()
    drawLine(C.muted, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(C.muted, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
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
            OutlineButton("Paste", height = 34.dp) { clipboard.getText()?.text?.let { onChange(it.take(limit)) } }
            if (value.isNotEmpty()) LinkText("Clear") { onChange("") }
            if (multiline) Text("${value.length} / $limit", style = zen(10.sp, FontWeight.Medium, C.muted))
        }
    }
}
