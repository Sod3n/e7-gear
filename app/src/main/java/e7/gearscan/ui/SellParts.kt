package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
        ShareBar(found, C.blue, Modifier.weight(1f))
        ShareBar(cost, C.deepPink, Modifier.weight(1f))
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

/** Stop-rule test on a real inventory (gear_cleanup/stop_rules, sweep_p50): keep-rolling chance %, good pieces kept, full +0->+15 enhances spent. */
internal object KeepCurve {
    const val POSSIBLE = 85.3
    const val ALL_COST = 846.1
    val points: List<Triple<Double, Double, Double>> = listOf(
        Triple(0.05, 85.24, 206.5),
        Triple(0.1, 85.22, 204.2),
        Triple(0.2, 85.17, 201.4),
        Triple(0.3, 85.07, 197.8),
        Triple(0.5, 84.91, 193.0),
        Triple(0.75, 84.68, 188.5),
        Triple(1.0, 84.34, 182.5),
        Triple(1.5, 83.46, 170.4),
        Triple(2.0, 82.96, 165.0),
        Triple(3.0, 81.93, 156.8),
        Triple(4.0, 80.52, 146.6),
        Triple(5.0, 79.39, 139.3),
        Triple(6.5, 77.30, 127.3),
        Triple(8.0, 75.65, 120.4),
        Triple(10.0, 73.69, 112.8),
        Triple(12.5, 70.44, 101.3),
        Triple(15.0, 68.07, 94.1),
        Triple(20.0, 63.92, 83.4),
        Triple(25.0, 58.77, 71.8),
        Triple(30.0, 53.86, 61.8),
        Triple(40.0, 48.01, 52.0),
        Triple(50.0, 43.57, 45.5),
    )
}

@Composable
internal fun KeepRollingSlider(value: Double, commit: (Double) -> Unit) {
    val pts = KeepCurve.points
    var index by remember(value) { mutableIntStateOf(pts.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(pts[it].first / value.coerceAtLeast(1e-4))) } ?: 0) }
    val (pct, good, cost) = pts[index]
    val stricter = pts.getOrNull(index + 1)
    val extra = stricter?.let { (cost - it.third) / (good - it.second).coerceAtLeast(1e-6) }
    Text("Keep enhancing while a piece's chance to reach your ES bar is at least", style = zen(13.sp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (pct < 1) String.format(java.util.Locale.US, "%.2g%%", pct) else "${fmtNum(pct)}%", style = dela(30.sp, C.blue))
        Text("1 in ${kotlin.math.round(100 / pct).toInt()}${if (kotlin.math.abs(pct - 1.0) < 1e-9) " · default" else ""}", style = zen(13.sp, color = C.muted), modifier = Modifier.padding(bottom = 6.dp))
    }
    androidx.compose.material3.Slider(value = index.toFloat(), onValueChange = { index = it.toInt().coerceIn(pts.indices) }, onValueChangeFinished = { commit(pts[index].first) },
        valueRange = 0f..(pts.size - 1).toFloat(), steps = (pts.size - 2).coerceAtLeast(0),
        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.pink, activeTrackColor = C.pink, inactiveTrackColor = C.line,
            activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Keep-rolling chance" })
    Row(Modifier.fillMaxWidth()) {
        Text("keep rolling more", style = zen(10.sp, color = C.muted), modifier = Modifier.weight(1f))
        Text("stop sooner", style = zen(10.sp, color = C.muted))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Good pieces kept", "${good.toInt()} of ${KeepCurve.POSSIBLE.toInt()}", (good / KeepCurve.POSSIBLE).toFloat(), C.blue, Modifier.weight(1f))
        StatTile("Total gold", EsForecast.goldText(cost * EsForecast.FULL_GOLD), (cost / KeepCurve.ALL_COST).toFloat(), C.deepPink, Modifier.weight(1f))
        StatTile("Each extra piece", extra?.let { EsForecast.goldText(it * EsForecast.FULL_GOLD) } ?: "—", null, C.ink, Modifier.weight(1f))
    }
    Text("Measured on a real inventory: 921 unfinished pieces, ${KeepCurve.POSSIBLE.toInt()} of them can reach the bar; finishing all costs ${EsForecast.goldText(KeepCurve.ALL_COST * EsForecast.FULL_GOLD)} gold. " +
        "Each extra piece: gold per good piece kept compared with one step stricter.", style = zen(11.sp, color = C.muted))
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
