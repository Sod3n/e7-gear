package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.data.fmtNum

/** Label, live value and a slider; saved when the finger lifts. The row lights up while dragging; tap the value to type it. */
@KeyboardInput
@Composable
fun ValueSlider(label: String, icon: @Composable () -> Unit, value: Double, min: Double, max: Double, step: Double, text: (Double) -> String, enabled: Boolean = true,
                         offStop: Boolean = false, commit: (Double) -> Unit) {
    // offStop: one extra stop left of [min] that means "off"; the caller gets a value below min for it
    val low = if (offStop) min - step else min
    var v by remember(value) { mutableStateOf(value.coerceIn(low, max)) }
    val off = offStop && v < min
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = source.collectIsDraggedAsState().value || source.collectIsPressedAsState().value
    fun snap(x: Double) = if (offStop && x < min - step / 2) low else (min + kotlin.math.round((x - min) / step) * step).coerceIn(min, max)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon(); Text(label, style = zen(13.sp, color = if (enabled && !off) C.ink else C.muted), modifier = Modifier.weight(1f))
            if (!enabled) Text(text(v), style = dela(15.sp, C.faint))
            else TypedNumber(text(v), v, active, dim = off) { typed -> v = snap(typed); commit(v) }
        }
        val n = ((max - low) / step).toInt().coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SliderTrack(v.toFloat(), { v = snap(it.toDouble()) }, { commit(v) }, low.toFloat()..max.toFloat(), if (n <= 200) n - 1 else 0, source, active, label, Modifier.weight(1f), enabled, quiet = off)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SliderTrack(value: Float, change: (Float) -> Unit, done: () -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int,
                         source: androidx.compose.foundation.interaction.MutableInteractionSource, active: Boolean, label: String, modifier: Modifier, enabled: Boolean = true, quiet: Boolean = false) {
    val none = androidx.compose.ui.graphics.Color.Transparent
    val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = if (quiet) C.faint else C.pink, activeTrackColor = if (quiet) C.line else C.pink, inactiveTrackColor = C.line,
        activeTickColor = none, inactiveTickColor = none, disabledThumbColor = C.faint, disabledActiveTrackColor = C.line,
        disabledInactiveTrackColor = C.line, disabledActiveTickColor = none, disabledInactiveTickColor = none)
    androidx.compose.material3.Slider(value = value, onValueChange = change, onValueChangeFinished = done, valueRange = range, steps = steps,
        interactionSource = source, colors = colors, enabled = enabled,
        thumb = { androidx.compose.material3.SliderDefaults.Thumb(source, colors = colors, enabled = enabled, thumbSize = if (active) androidx.compose.ui.unit.DpSize(8.dp, 52.dp) else androidx.compose.ui.unit.DpSize(4.dp, 44.dp)) },
        modifier = modifier.height(48.dp).semantics { contentDescription = label })
}

/** The value; tapping it opens the number keyboard, Done or leaving the field applies it. */
@KeyboardInput
@Composable
fun TypedNumber(shown: String, value: Double, active: Boolean, size: Int = 15, dim: Boolean = false, apply: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Text(shown, style = dela(if (active) (size + 5).sp else size.sp, if (active) C.deepPink else if (dim) C.faint else C.blue),
            modifier = Modifier.tap { editing = true }.semantics { contentDescription = "Type a value" })
        return
    }
    val plain = if (value == kotlin.math.floor(value)) value.toLong().toString() else fmtNum(value)
    var typed by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(plain, androidx.compose.ui.text.TextRange(0, plain.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    fun finish() { typed.text.replace(',', '.').toDoubleOrNull()?.let(apply); editing = false }
    val shape = RoundedCornerShape(8.dp)
    LegacyStatFieldValue(typed, { typed = it }, dela(size.sp, C.deepPink),
        androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        androidx.compose.foundation.text.KeyboardActions(onDone = { finish() }),
        Modifier.width(84.dp).clip(shape).background(C.field).border(1.5.dp, C.edge, shape).padding(horizontal = 8.dp, vertical = 4.dp)
            .focusRequester(focus).onFocusChanged { if (focused && !it.isFocused) finish(); focused = it.isFocused })
    LaunchedEffect(Unit) { focus.requestFocus() }
}


