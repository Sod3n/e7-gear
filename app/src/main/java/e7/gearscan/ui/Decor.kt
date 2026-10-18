package e7.gearscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TIER_LABELS = listOf("Just geared", "A bit of good gear", "Good gear", "Some perfect gear", "Best of account")

fun tierLabel(t: Int) = "$t · ${TIER_LABELS[t - 1]}"

val PaperBrush = Brush.verticalGradient(listOf(Color(0xFFF3E9D6), Color(0xFFECDFC6)))

/** Pink dot screen marking the selected card. */
fun Modifier.halftone(): Modifier = drawBehind {
    val step = 8.dp.toPx()
    val r = 1.4.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(Color(0xFFF5B3D1), r, Offset(x, y)); x += step }
        y += step
    }
}

/** Pink diagonal bands marking what is being edited now. */
fun Modifier.hatch(): Modifier = drawBehind {
    drawRect(Color(0xFFFDE3EF))
    val band = 8.dp.toPx()
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawPath(Path().apply {
                moveTo(x, size.height); lineTo(x + band, size.height); lineTo(x + band + size.height, 0f); lineTo(x + size.height, 0f); close()
            }, Color(0xFFF9D0E3))
            x += band * 2
        }
    }
}

/** Seigaiha waves under a sheet's handle. */
@Composable
fun SeigaihaStrip(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val tile = 24.dp.toPx()
        val k = size.height / 20f
        clipRect {
            var x = 0f
            while (x <= size.width + tile) {
                for ((r, a) in listOf(6f to 0.55f, 11f to 0.4f, 16f to 0.25f))
                    drawCircle(Color(0xFFFF4FA8).copy(alpha = a), r * k, Offset(x, size.height), style = Stroke(k))
                x += tile
            }
        }
    }
}

/** A tilted outlined stamp: 装 equipped, 済 done. */
@Composable
fun Stamp(char: String, text: String? = null, color: Color = C.deepPink) {
    Row(Modifier.rotate(-6f).clip(RoundedCornerShape(4.dp)).background(C.card.copy(alpha = 0.7f)).border(2.dp, color, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(char, style = dela(11.sp, color))
        if (text != null) Text(text, style = dela(10.sp, color))
    }
}

@Composable
fun RankChip(n: Int) {
    Box(Modifier.size(width = 34.dp, height = 22.dp).hardShadow(C.pink, 2.dp, 2.dp, 6.dp).clip(RoundedCornerShape(6.dp)).background(C.ink),
        contentAlignment = Alignment.Center) { FitText("#$n", dela(11.sp, C.card)) }
}

/** Gear match with a build: blue when nearly there, pink when far. */
@Composable
fun MatchChip(pct: Int, modifier: Modifier = Modifier) {
    val bg = when { pct >= 90 -> C.blue; pct >= 60 -> C.ink; else -> C.deepPink }
    FitText("$pct%", dela(11.sp, C.card), modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Five small boxes filled up to the tier. */
@Composable
fun TierDots(tier: Int, box: Dp = 9.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) Box(Modifier.size(box).clip(RoundedCornerShape(2.5.dp)).background(if (i <= tier) C.pink else C.line))
    }
}

/** 1–5 bar, tap or drag; filled up to the chosen tier, which is raised. */
@Composable
fun TierBar(tier: Int, modifier: Modifier = Modifier, height: Dp = 44.dp, onChange: (Int) -> Unit) {
    val set by rememberUpdatedState(onChange)
    Row(modifier.fillMaxWidth().height(height + 3.dp)
        .pointerInput(Unit) {
            fun at(x: Float) = (x / size.width * 5).toInt().coerceIn(0, 4) + 1
            detectTapGestures { set(at(it.x)) }
        }
        .pointerInput(Unit) {
            fun at(x: Float) = (x / size.width * 5).toInt().coerceIn(0, 4) + 1
            detectHorizontalDragGestures { change, _ -> set(at(change.position.x)) }
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..5) {
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.weight(1f).height(height).then(if (i == tier) Modifier.hardShadow(C.ink, 2.dp, 3.dp, 10.dp) else Modifier)
                .clip(shape).background(if (i <= tier) C.pink else C.card).border(2.5.dp, C.ink, shape),
                contentAlignment = Alignment.Center) { Text("$i", style = dela(15.sp)) }
        }
    }
}

/** 0–3 weight picker in the same style as the tier bar. */
@Composable
fun WeightBar(weight: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(47.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0..3) {
            val shape = RoundedCornerShape(10.dp)
            Box(Modifier.weight(1f).height(44.dp).then(if (i == weight) Modifier.hardShadow(C.ink, 2.dp, 3.dp, 10.dp) else Modifier)
                .clip(shape).background(if (i <= weight) C.pink else C.card).border(2.5.dp, C.ink, shape).tap { onChange(i) },
                contentAlignment = Alignment.Center) { Text("$i", style = dela(15.sp)) }
        }
    }
}
