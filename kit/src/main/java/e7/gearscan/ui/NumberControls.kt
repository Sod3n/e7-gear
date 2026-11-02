package e7.gearscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import e7.gearscan.data.fmtNum
import kotlin.math.floor
import kotlin.math.round

/** Numeric entry is touch controls only. No editable text or input connection. */
@Composable
@OptIn(LinkStyle::class)
fun NumberValue(value: Double, label: String, minimum: Double, maximum: Double, modifier: Modifier = Modifier,
                decimal: Boolean = true, precision: Int = if(decimal)1 else 0, allowUnset: Boolean = true, onChange: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.heightIn(min = 30.dp).tap { open = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        FitText(if (value.isNaN()) "—" else numberText(value,precision), dela(13.sp, if (value.isNaN()) C.faint else C.ink))
    }
    if (open) {
        var draft by remember { mutableStateOf((if (value.isNaN()) minimum else value).coerceIn(minimum, maximum)) }
        Dialog(onDismissRequest = { open = false }) {
            Card(shadow = null, padding = PaddingValues(16.dp)) {
                Text(label, style = dela(18.sp))
                VSpace(12.dp)
                NumberAdjuster(draft, minimum = minimum, maximum = maximum, decimal = decimal,precision=precision) { draft = it }
                VSpace(12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if(allowUnset)LinkText("Unset") { onChange(Double.NaN); open = false }
                    LinkText("Cancel") { open = false }
                }
                VSpace(14.dp)
                PinkButton("Use value", Modifier.fillMaxWidth()) { onChange(draft); open = false }
            }
        }
    }
}

@Composable
fun NumberAdjuster(value: Double, minimum: Double, maximum: Double, modifier: Modifier = Modifier,
                   decimal: Boolean = true, precision: Int = if(decimal)1 else 0, onChange: (Double) -> Unit) {
    val span = maximum - minimum
    val choices = (if(precision>1) listOf(.0001,.001,.01,.1,1.0,10.0,100.0,1000.0) else if (decimal) listOf(.1, 1.0, 10.0, 100.0, 1000.0) else listOf(1.0, 10.0, 100.0, 1000.0)).filter { it <= span + 1e-9 }
    var step by remember(minimum, maximum) { mutableStateOf(if(precision>1) .01 else if (span >= 1000) 100.0 else if (span >= 1) 1.0 else .1) }
    val low = minimum
    val high = maximum
    fun set(v: Double) { val factor=Math.pow(10.0,precision.toDouble());onChange((round(v*factor)/factor).coerceIn(minimum,maximum)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberAction("−", "Decrease value") { set(value - step) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FitText(numberText(value,precision), dela(24.sp, C.blue)) }
            NumberAction("+", "Increase value") { set(value + step) }
        }
        Text("Change by", style = zen(11.sp, FontWeight.Medium, C.muted))
        CardGrid(choices, columns = choices.size.coerceIn(1, if(precision>1)4 else 5), gap = 5.dp) { n, m ->
            val shape = RoundedCornerShape(8.dp)
            Box(m.height(36.dp).clip(shape).background(if (step == n) C.pink else C.field).border(1.5.dp, if (step == n) C.edge else C.line, shape)
                .tap { step = n }.semantics { contentDescription = "Step ${numberText(n,precision)}" }, contentAlignment = Alignment.Center) { FitText(numberText(n,precision), zen(11.sp, color = if (step == n) C.onAccent else C.ink)) }
        }
        NumberRangeSlider(value, minimum, maximum, step, Modifier.fillMaxWidth().height(36.dp), onChange)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtNum(low), style = zen(10.sp, color = C.muted)); Text(fmtNum(high), style = zen(10.sp, color = C.muted))
        }
    }
}

private fun numberText(v:Double,precision:Int)=if(precision<=1) fmtNum(v) else String.format(java.util.Locale.US,"%.${precision}f",v).trimEnd('0').trimEnd('.')

@Composable
private fun NumberAction(text: String, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(48.dp).clip(shape).background(C.field).border(2.dp, C.edge, shape).tap(onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(text, style = dela(20.sp))
    }
}

/** Shared touch slider: compact overlays choose their own height without duplicating range math. */
@Composable
fun NumberRangeSlider(value: Double, minimum: Double, maximum: Double, step: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    val low = minimum
    val high = maximum
        val changed by rememberUpdatedState(onChange)
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        Canvas(modifier.semantics { contentDescription = "Value slider" }
            .pointerInput(low, high, step) {
                fun at(x: Float): Double {
                    val inset = 10 * density
                    val f = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    return (round((low + f * (high - low)) / step) * step).coerceIn(minimum, maximum)
                }
                detectTapGestures { changed(at(it.x)) }
            }.pointerInput(low, high, step) {
                fun at(x: Float): Double {
                    val inset = 10 * density
                    val f = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    return (round((low + f * (high - low)) / step) * step).coerceIn(minimum, maximum)
                }
                detectHorizontalDragGestures { change, _ -> change.consume(); changed(at(change.position.x)) }
            }) {
            val inset = 10.dp.toPx()
            val x = inset + ((value - low) / (high - low).coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat() * (size.width - inset * 2)
            val y = size.height / 2
            drawLine(C.line, Offset(inset, y), Offset(size.width - inset, y), 5.dp.toPx(), StrokeCap.Round)
            drawLine(C.pink, Offset(inset, y), Offset(x, y), 5.dp.toPx(), StrokeCap.Round)
            drawCircle(C.ink, 10.dp.toPx(), Offset(x, y)); drawCircle(C.card, 6.dp.toPx(), Offset(x, y))
        }
}
