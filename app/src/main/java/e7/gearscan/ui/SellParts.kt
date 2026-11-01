package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.data.*
import e7.gearscan.opt.*

internal fun chanceText(c: Double) = when {
    c >= .995 -> "100%"
    c >= .1 -> "${(c * 100).toInt()}%"
    c > 0 -> "1 in ${kotlin.math.round(1 / c).toLong()}"
    else -> "0%"
}

/** Short chip: how many finishes reach the bar, coloured by the keep-rolling chance. */
@Composable
internal fun EsChip(es: EsForecast.Es, rollOn: Double) {
    val (text, good) = when {
        !es.supported || es.bar == null -> "No forecast" to null
        es.reached -> "At bar ${es.bar}" to true
        es.finishShare == 0.0 -> "Below bar ${es.bar}" to false
        es.chance == 0.0 && es.possible -> "Rare only" to false
        else -> "${chanceText(es.chance)} reach ${es.bar}" to es.worthRolling(rollOn)
    }
    val shape = RoundedCornerShape(999.dp)
    val tint = when (good) { true -> C.blue; false -> C.deepPink; null -> C.muted }
    Box(Modifier.clip(shape).background(tint.copy(alpha = .13f)).border(1.5.dp, tint, shape).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = zen(11.sp, color = tint))
    }
}

/** ES now (solid), usual at +15 (light), best (tick) and the bar (pink marker). */
@Composable
internal fun EsBar(es: EsForecast.Es, modifier: Modifier = Modifier) {
    val top = maxOf(es.bar ?: 0, es.best?.toInt() ?: 0, es.now, 1) * 1.08f
    fun f(v: Number) = (v.toFloat() / top).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(14.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line))
        es.typical?.let { Box(Modifier.align(Alignment.CenterStart).width(w * f(it)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.blue.copy(alpha = .35f))) }
        Box(Modifier.align(Alignment.CenterStart).width(w * f(es.now)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.blue))
        es.best?.let { Box(Modifier.align(Alignment.CenterStart).offset(x = w * f(it) - 1.dp).width(2.dp).height(8.dp).background(C.blue.copy(alpha = .6f))) }
        es.bar?.let { Box(Modifier.offset(x = w * f(it) - 1.5.dp).width(3.dp).fillMaxHeight().background(C.pink)) }
    }
}

@Composable
internal fun EsLegend(es: EsForecast.Es) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("now ${es.now}", style = zen(10.sp, color = C.muted))
        es.typical?.takeIf { es.finishShare > 0 }?.let { Text("usual +15 ${it.toInt()}", style = zen(10.sp, color = C.muted)) }
        es.best?.takeIf { es.finishShare > 0 }?.let { Text("best ${it.toInt()}", style = zen(10.sp, color = C.muted)) }
        Spacer(Modifier.weight(1f))
        es.bar?.let { Text("bar $it", style = zen(10.sp, color = C.deepPink)) }
    }
}

@Composable
internal fun MainStatLine(item: Item, size: Int = 10) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatIcon(item.mainType.stat, (size + 2).dp, C.muted)
        Text("${item.mainType.format(item.mainValue)} · Lv.${item.level}", style = zen(size.sp, color = C.muted))
    }
}

/** n dots, one lit: "1 in n" as a picture. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OddsDots(n: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(n) { i -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (i == 0) C.blue else C.line)) }
        }
        Text("1 lit of $n finishes reaches the bar", style = zen(11.sp, color = C.muted))
    }
}

/** A section that opens in place: a full-width row with a chevron, never a link. */
@Composable
internal fun Fold(title: String, open: Boolean, toggle: () -> Unit, content: @Composable () -> Unit) {
    Card(shadowOffset = 3.dp, radius = 14.dp, border = 2.dp, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().tap(toggle).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = zen(13.sp), modifier = Modifier.weight(1f))
            Text("›", style = zen(16.sp), modifier = Modifier.rotate(if (open) -90f else 90f))
        }
        if (open) Column(Modifier.fillMaxWidth().background(C.field).border(1.dp, C.line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Two short bars side by side: a share found (blue) and a share paid (pink). */
@Composable
internal fun SharePair(label: String, found: Float, cost: Float, best: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (best) C.lightPink else C.card).border(if (best) 2.dp else 1.dp, if (best) C.edge else C.line, shape)
        .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = if (best) dela(13.sp) else zen(12.sp), modifier = Modifier.width(56.dp))
        ShareBar(found, C.blue, Modifier.weight(1f).fillMaxHeight())
        ShareBar(cost, C.deepPink, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ShareBar(v: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text("${(v * 100).toInt()}%", style = zen(11.sp, color = color), modifier = Modifier.width(30.dp))
    }
}

@Composable
internal fun KeptRow(label: String, good: Int, of: Int, total: String, extra: String, chosen: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (chosen) C.lightPink else C.card).border(if (chosen) 2.dp else 1.dp, if (chosen) C.edge else C.line, shape)
        .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = if (chosen) dela(12.sp) else zen(12.sp), modifier = Modifier.width(56.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line)) {
                Box(Modifier.fillMaxWidth(good.toFloat() / of).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(C.blue))
            }
            Text("$good", style = zen(11.sp, color = C.blue), modifier = Modifier.width(20.dp))
        }
        Text(total, style = zen(11.sp), modifier = Modifier.width(44.dp))
        Text(extra, style = zen(11.sp, color = C.deepPink), modifier = Modifier.width(66.dp))
    }
}

@Composable
internal fun KeepRollingSlider(value: Double, curve: e7.gearscan.opt.KeepCurve.Result?, busy: Boolean, find: () -> Unit, commit: (Double) -> Unit) {
    val ts = e7.gearscan.opt.KeepCurve.THRESHOLDS
    var index by remember(value) { mutableIntStateOf(ts.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(ts[it] * 100 / value.coerceAtLeast(1e-4))) } ?: 0) }
    val pct = ts[index] * 100
    Text("Keep enhancing while a piece's chance to reach your ES bar is at least", style = zen(13.sp))
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = source.collectIsDraggedAsState().value || source.collectIsPressedAsState().value
    fun nearest(p: Double) = ts.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(ts[it] * 100 / p.coerceAtLeast(1e-4))) } ?: 0
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TypedNumber(if (pct < 1) String.format(java.util.Locale.US, "%.2g%%", pct) else "${fmtNum(pct)}%", pct, active, 30) { p -> index = nearest(p); commit(ts[index] * 100) }
        if (kotlin.math.abs(pct - 1.0) < 1e-9) Text("default", style = zen(12.sp, color = C.muted), modifier = Modifier.padding(bottom = 6.dp))
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else androidx.compose.ui.graphics.Color.Transparent).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderTrack(index.toFloat(), { index = it.toInt().coerceIn(ts.indices) }, { commit(ts[index] * 100) }, 0f..(ts.size - 1).toFloat(), (ts.size - 2).coerceAtLeast(0),
            source, active, "Keep-rolling chance", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("keep rolling more", style = zen(10.sp, color = C.muted), modifier = Modifier.weight(1f))
        Text("stop sooner", style = zen(10.sp, color = C.muted))
    }
    // same height whether the numbers are in or not, so nothing below moves
    Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (curve == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Spinner(); Text("Working…", style = zen(13.sp, color = C.muted)) }
                else OutlineButton("Show my numbers", Modifier.fillMaxWidth(), height = 52.dp, onClick = find)
            }
        } else {
            val good = curve.good[index]; val gold = curve.gold[index]
            val extra = if (index + 1 < ts.size) (gold - curve.gold[index + 1]) / (good - curve.good[index + 1]).coerceAtLeast(1e-6) else null
            StatTile("Good pieces kept", "${kotlin.math.round(good).toInt()} of ${kotlin.math.round(curve.possible).toInt()}", (good / curve.possible.coerceAtLeast(1e-9)).toFloat(), C.blue, Modifier.weight(1f).fillMaxHeight())
            StatTile("Total gold", EsForecast.goldText(gold), (gold / curve.allGold.coerceAtLeast(1.0)).toFloat(), C.deepPink, Modifier.weight(1f).fillMaxHeight())
            StatTile("Each extra piece", extra?.takeIf { it.isFinite() && good - curve.good[index + 1] > 1e-3 }?.let { EsForecast.goldText(it) } ?: "—", null, C.ink, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, fill: Float?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.padding(bottom = 3.dp).hardShadow(C.shadow, 0.dp, 3.dp, 12.dp).clip(shape).background(C.card).border(1.5.dp, C.edge, shape).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = zen(10.sp, color = C.muted), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        FitText(value, dela(16.sp, color))
        if (fill != null) Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        } else Spacer(Modifier.height(5.dp))
    }
}

/** Label, live value and a slider; saved when the finger lifts. The row lights up while dragging; tap the value to type it. */
@Composable
internal fun ValueSlider(label: String, icon: @Composable () -> Unit, value: Double, min: Double, max: Double, step: Double, text: (Double) -> String, enabled: Boolean = true, commit: (Double) -> Unit) {
    var v by remember(value) { mutableStateOf(value.coerceIn(min, max)) }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = source.collectIsDraggedAsState().value || source.collectIsPressedAsState().value
    fun snap(x: Double) = (min + kotlin.math.round((x - min) / step) * step).coerceIn(min, max)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon(); Text(label, style = zen(13.sp, color = if (enabled) C.ink else C.muted), modifier = Modifier.weight(1f))
            if (enabled) TypedNumber(text(v), v, active) { typed -> v = snap(typed); commit(v) } else Text(text(v), style = dela(15.sp, C.faint))
        }
        val n = ((max - min) / step).toInt().coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SliderTrack(v.toFloat(), { v = snap(it.toDouble()) }, { commit(v) }, min.toFloat()..max.toFloat(), if (n <= 200) n - 1 else 0, source, active, label, Modifier.weight(1f), enabled)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SliderTrack(value: Float, change: (Float) -> Unit, done: () -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int,
                         source: androidx.compose.foundation.interaction.MutableInteractionSource, active: Boolean, label: String, modifier: Modifier, enabled: Boolean = true) {
    val none = androidx.compose.ui.graphics.Color.Transparent
    val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.pink, activeTrackColor = C.pink, inactiveTrackColor = C.line,
        activeTickColor = none, inactiveTickColor = none, disabledThumbColor = C.faint, disabledActiveTrackColor = C.line,
        disabledInactiveTrackColor = C.line, disabledActiveTickColor = none, disabledInactiveTickColor = none)
    androidx.compose.material3.Slider(value = value, onValueChange = change, onValueChangeFinished = done, valueRange = range, steps = steps,
        interactionSource = source, colors = colors, enabled = enabled,
        thumb = { androidx.compose.material3.SliderDefaults.Thumb(source, colors = colors, enabled = enabled, thumbSize = if (active) androidx.compose.ui.unit.DpSize(8.dp, 52.dp) else androidx.compose.ui.unit.DpSize(4.dp, 44.dp)) },
        modifier = modifier.height(48.dp).semantics { contentDescription = label })
}

/** The value; tapping it opens the number keyboard, Done or leaving the field applies it. */
@Composable
internal fun TypedNumber(shown: String, value: Double, active: Boolean, size: Int = 15, apply: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Text(shown, style = dela(if (active) (size + 5).sp else size.sp, if (active) C.deepPink else C.blue),
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


/** Two handles over +0..+15; the row lights up while dragging; saved when the finger lifts. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EnhanceRange(low: Int, high: Int, commit: (Int, Int) -> Unit) {
    var r by remember(low, high) { mutableStateOf(low.toFloat()..high.toFloat()) }
    val start = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val end = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = start.collectIsDraggedAsState().value || end.collectIsDraggedAsState().value || start.collectIsPressedAsState().value || end.collectIsPressedAsState().value
    val none = androidx.compose.ui.graphics.Color.Transparent
    val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.pink, activeTrackColor = C.pink, inactiveTrackColor = C.line, activeTickColor = none, inactiveTickColor = none)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else none).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enhancement", style = zen(13.sp), modifier = Modifier.weight(1f))
            Text("+${r.start.toInt()} to +${r.endInclusive.toInt()}", style = dela(if (active) 20.sp else 15.sp, if (active) C.deepPink else C.blue))
        }
        val thumb = { s: androidx.compose.foundation.interaction.MutableInteractionSource -> @Composable {
            androidx.compose.material3.SliderDefaults.Thumb(s, colors = colors, thumbSize = if (active) androidx.compose.ui.unit.DpSize(8.dp, 52.dp) else androidx.compose.ui.unit.DpSize(4.dp, 44.dp)) } }
        androidx.compose.material3.RangeSlider(value = r, onValueChange = { v -> r = kotlin.math.round(v.start)..kotlin.math.round(v.endInclusive) },
            onValueChangeFinished = { commit(r.start.toInt(), r.endInclusive.toInt()) }, valueRange = 0f..15f, steps = 14, colors = colors,
            startInteractionSource = start, endInteractionSource = end,
            startThumb = { thumb(start)() }, endThumb = { thumb(end)() },
            modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Enhancement range" })
    }
}
