package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.field).border(1.5.dp, C.line, RoundedCornerShape(10.dp))
            .tap(toggle).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = zen(13.sp), modifier = Modifier.weight(1f))
            Text("›", style = zen(15.sp, color = C.muted), modifier = Modifier.rotate(if (open) -90f else 90f))
        }
        if (open) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
