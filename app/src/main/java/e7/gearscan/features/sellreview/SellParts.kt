package e7.gearscan.features.sellreview

import e7.gearscan.ui.*
import e7.gearscan.value.Chance

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

/** Short chip: how many finishes reach the bar, coloured by the keep-rolling chance. */
@Composable
internal fun EsChip(es: EsForecast.Es, rollOn: Double) {
    val (text, good) = when {
        !es.supported || es.bar == null -> "No forecast" to null
        es.reached -> "At bar ${es.bar}" to true
        es.finishShare == 0.0 -> "Below bar ${es.bar}" to false
        es.chance == 0.0 && es.possible -> "Rare only" to false
        else -> "${Chance(es.chance).text} reach ${es.bar}" to es.worthRolling(rollOn)
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

private const val ESTIMATE = "This is an estimate: an average over all the ways the rolls can go. Your own results will be higher or lower."

@Composable
@OptIn(KeyboardInput::class)
internal fun KeepRollingSlider(value: Double, curve: e7.gearscan.opt.KeepCurve.Result?, busy: Boolean, find: () -> Unit, explain: (String, String) -> Unit, commit: (Double) -> Unit) {
    val ts = e7.gearscan.opt.KeepCurve.THRESHOLDS
    var index by remember(value) { mutableIntStateOf(ts.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(ts[it] * 100 / value.coerceAtLeast(1e-4))) } ?: 0) }
    val pct = ts[index] * 100
    Text("Keep enhancing while a piece's chance to reach your ES bar is at least", style = zen(13.sp))
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = source.collectIsDraggedAsState().value || source.collectIsPressedAsState().value
    fun nearest(p: Double) = ts.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(ts[it] * 100 / p.coerceAtLeast(1e-4))) } ?: 0
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TypedNumber(Chance(ts[index]).text, pct, active, 30) { p -> index = nearest(p); commit(ts[index] * 100) }
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
            val at = Chance(ts[index]).text
            val g = kotlin.math.round(good).toInt(); val all = kotlin.math.round(curve.possible).toInt()
            // estimates: shown rounded and with ~ so they never read as exact
            fun rough(x: Double): String { val m = x / 1e6; return "~" + when { m >= 100 -> "${kotlin.math.round(m).toLong()}M"; m >= 1 -> String.format(java.util.Locale.US, "%.1fM", m); else -> "${kotlin.math.round(x / 1e3).toLong()}k" } }
            val extraText = extra?.takeIf { it.isFinite() && good - curve.good[index + 1] > 1e-3 }?.let { rough(it) }
            StatTile("Good pieces kept", "~$g of $all", (good / curve.possible.coerceAtLeast(1e-9)).toFloat(), C.blue, Modifier.weight(1f).fillMaxHeight(), short = "~$g") {
                explain("Good pieces kept", "Pieces from your list (${curve.pieces}) that end at or above your ES bar. If every one were taken to +15, about $all would make it. " +
                    "At $at, a piece stops after any +3 that leaves it below a $at chance, so about $g still make it. Counted over every way the remaining rolls can land, weighted by how likely each is. " + ESTIMATE)
            }
            StatTile("Total gold", rough(gold), (gold / curve.allGold.coerceAtLeast(1.0)).toFloat(), C.deepPink, Modifier.weight(1f).fillMaxHeight()) {
                explain("Total gold", "Enhance gold for the pieces in your list, from their current level. Each +3 is paid only while a piece is still being rolled; " +
                    "it stops once its chance drops below $at. Finishing every piece to +15 would cost ${rough(curve.allGold)}. " +
                    "Uses the enhancement-stone price; gold already spent and gold from selling are not counted. " + ESTIMATE)
            }
            StatTile("Each extra piece", extraText ?: "—", null, C.ink, Modifier.weight(1f).fillMaxHeight()) {
                explain("Each extra piece", "What one more good piece costs when you roll a little longer: the gold at $at minus the gold one step stricter on the slider, " +
                    "divided by how many more good pieces that buys." + (extraText?.let { " Here about $it per piece." } ?: " At the strictest setting there is no step to compare with.") + " " + ESTIMATE)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, fill: Float?, color: androidx.compose.ui.graphics.Color, modifier: Modifier, short: String? = null, explain: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.padding(bottom = 3.dp).hardShadow(C.shadow, 0.dp, 3.dp, 12.dp).clip(shape).background(C.card).border(1.5.dp, C.edge, shape).tap(explain).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = zen(10.sp, color = C.muted), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (explain != null) Text("?", style = dela(11.sp, C.muted))
        }
        FitText(value, dela(16.sp, color), short = short)
        if (fill != null) Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        } else Spacer(Modifier.height(5.dp))
    }
}

/** Two handles on the upgrade steps +0, +3 .. +15; a step covers the levels up to the next one (+12 holds +12..+14). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EnhanceRange(low: Int, high: Int, commit: (Int, Int) -> Unit) {
    var r by remember(low, high) { mutableStateOf((low / 3).toFloat()..(if (high >= 15) 5 else high / 3).toFloat()) }
    val start = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val end = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val active = start.collectIsDraggedAsState().value || end.collectIsDraggedAsState().value || start.collectIsPressedAsState().value || end.collectIsPressedAsState().value
    val none = androidx.compose.ui.graphics.Color.Transparent
    val colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.pink, activeTrackColor = C.pink, inactiveTrackColor = C.line, activeTickColor = none, inactiveTickColor = none)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else none).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enhancement", style = zen(13.sp), modifier = Modifier.weight(1f))
            Text("+${r.start.toInt() * 3} to +${r.endInclusive.toInt() * 3}", style = dela(if (active) 20.sp else 15.sp, if (active) C.deepPink else C.blue))
        }
        val thumb = { s: androidx.compose.foundation.interaction.MutableInteractionSource -> @Composable {
            androidx.compose.material3.SliderDefaults.Thumb(s, colors = colors, thumbSize = if (active) androidx.compose.ui.unit.DpSize(8.dp, 52.dp) else androidx.compose.ui.unit.DpSize(4.dp, 44.dp)) } }
        androidx.compose.material3.RangeSlider(value = r, onValueChange = { v -> r = kotlin.math.round(v.start)..kotlin.math.round(v.endInclusive) },
            onValueChangeFinished = { val hi = r.endInclusive.toInt(); commit(r.start.toInt() * 3, if (hi == 5) 15 else hi * 3 + 2) }, valueRange = 0f..5f, steps = 4, colors = colors,
            startInteractionSource = start, endInteractionSource = end,
            startThumb = { thumb(start)() }, endThumb = { thumb(end)() },
            modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Enhancement range" })
    }
}
