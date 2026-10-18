package e7.gearscan.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import e7.gearscan.data.Item
import e7.gearscan.data.Stat

/** Game-derived images live on the content site, not in the APK; the content names the base, so it can move. */
object GameImages {
    @Volatile var base: String = e7.gearscan.data.Content.DEFAULT_IMAGE_BASE
}

fun gameImage(path: String) = GameImages.base + path

val MissingImage = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE6DCCA))

fun installImageLoader(ctx: android.content.Context) {
    coil.Coil.setImageLoader(coil.ImageLoader.Builder(ctx).eventListener(object : coil.EventListener {
        override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) {
            e7.gearscan.data.Problems.report("image failed: ${request.data}")
        }
    }).build())
}

/** Shown after the app crashed: what happened, with a one-tap report. */
@Composable
fun CrashCard(model: e7.gearscan.AppModel) {
    val crash = model.lastCrash ?: return
    Card(shadow = null, radius = 14.dp, border = 2.dp) {
        Text("E7 Gear closed unexpectedly last time", style = dela(14.sp))
        VSpace(4.dp)
        Text(crash.lineSequence().first(), style = zen(12.sp, FontWeight.Medium, C.muted), maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkText("Report this") { model.go(e7.gearscan.Screen.Feedback(crash)); model.dismissCrash() }
            LinkText("Dismiss", color = C.muted) { model.dismissCrash() }
        }
    }
}

/** Draws a solid copy of the shape offset behind the content, like CSS box-shadow with no blur. */
fun Modifier.hardShadow(color: Color, dx: Dp, dy: Dp, radius: Dp): Modifier = drawBehind {
    drawRoundRect(color, topLeft = Offset(dx.toPx(), dy.toPx()), size = size,
        cornerRadius = CornerRadius(radius.toPx()))
}

fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 2.6f, w * 1.8f))))
}

fun Modifier.stripes(a: Color, b: Color, step: Dp = 5.dp, shape: Shape = CircleShape): Modifier = clip(shape).drawBehind {
    drawRect(b)
    val s = step.toPx()
    var x = -size.height
    while (x < size.width) {
        val p = Path().apply {
            moveTo(x, size.height); lineTo(x + s, size.height); lineTo(x + s + size.height, 0f); lineTo(x + size.height, 0f); close()
        }
        drawPath(p, a)
        x += 2 * s
    }
}

fun Modifier.tap(onClick: (() -> Unit)?): Modifier = if (onClick == null) this else composedClick(onClick)

private fun Modifier.composedClick(onClick: () -> Unit) = this.then(
    Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick))

@Composable
fun Card(
    modifier: Modifier = Modifier,
    shadow: Color? = C.ink,
    shadowOffset: Dp = 4.dp,
    radius: Dp = 16.dp,
    border: Dp = 2.5.dp,
    bg: Color = C.card,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    onClick: (() -> Unit)? = null,
    halftone: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        Modifier.fillMaxWidth().then(modifier)
            .then(if (shadow != null) Modifier.hardShadow(shadow, shadowOffset, shadowOffset, radius) else Modifier)
            .clip(shape).background(bg).then(if (halftone) Modifier.halftone() else Modifier).border(border, C.ink, shape).tap(onClick).padding(padding),
        content = content,
    )
}

/** One line that shrinks (to 70%) rather than wrapping or clipping; for numbers, ranks and button labels. */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    val align = when (textAlign) { TextAlign.Center -> Alignment.Center; TextAlign.End, TextAlign.Right -> Alignment.CenterEnd; else -> Alignment.CenterStart }
    // keeps the full-size line height, so a shrunk number never makes its row shorter
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { remember(style, this) { measurer.measure(" ", style).size.height.toDp() } }
    Box(modifier.heightIn(min = lineHeight), contentAlignment = align) {
        Text(text, style = style.copy(fontSize = style.fontSize * scale), textAlign = textAlign,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow && scale > 0.7f) scale -= 0.06f })
    }
}

@Composable
fun PinkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier.height(52.dp)
            .then(if (enabled) Modifier.hardShadow(C.ink, 3.dp, 4.dp, 26.dp) else Modifier)
            .clip(shape).background(if (enabled) C.pink else C.line).border(2.5.dp, if (enabled) C.ink else C.faint, shape)
            .tap(if (enabled) onClick else null),
        contentAlignment = Alignment.Center,
    ) { FitText(text, dela(16.sp, if (enabled) C.ink else C.muted), Modifier.padding(horizontal = 14.dp)) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, height: Dp = 52.dp, dashed: Boolean = false,
                  enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier.height(height).clip(shape).background(C.card)
            .then(if (dashed || !enabled) Modifier.dashedBorder(C.faint, 2.dp, height / 2) else Modifier.border(2.dp, C.ink, shape))
            .tap(if (enabled) onClick else null).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { FitText(text, zen(14.sp, color = if (enabled) C.ink else C.faint)) }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, bg: Color? = null, onClick: (() -> Unit)? = null,
         leading: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.clip(shape).background(bg ?: if (selected) C.pink else Color.Transparent).border(2.dp, C.ink, shape)
            .tap(onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        Text(text, style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Tag(text: String, bg: Color = C.pink, fg: Color = C.ink, border: Boolean = true, size: Int = 11) {
    Text(text, style = dela(size.sp, fg), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
        .then(if (border) Modifier.border(2.dp, C.ink, RoundedCornerShape(6.dp)) else Modifier)
        .padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun GhostTag() = Tag("幽 GHOST", bg = C.blue, fg = C.card, border = false, size = 10)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = dela(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(6.dp).drawBehind {
            val w = 2.dp.toPx()
            drawRect(C.ink, size = Size(size.width, w))
            drawRect(C.ink, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
        })
        trailing?.let { Text(it, style = zen(11.sp, FontWeight.Bold, C.muted)) }
    }
}

@Composable
fun ScreenTitle(title: String, kana: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitText(title, TitleStyle)
            kana?.let { Text(it, style = zen(12.sp, color = C.deepPink), modifier = Modifier.padding(bottom = 2.dp)) }
        }
        trailing?.invoke()
    }
}

@Composable
fun ScanChip(text: String, live: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(Modifier.clip(shape).background(if (live) C.ink else C.card).border(2.dp, C.ink, shape).tap(onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) PulseDot(8.dp) else Box(Modifier.size(8.dp).clip(CircleShape).background(C.blue))
        FitText(text, zen(12.sp, color = if (live) C.card else C.ink))
    }
}

@Composable
fun PulseDot(size: Dp) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.25f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(C.pink))
}

/** "‹ <previous screen>" for screens that can be reached from several places. */
@Composable
fun BackToPrevious(model: e7.gearscan.AppModel) {
    val label = when (model.stack.getOrNull(model.stack.size - 2)) {
        e7.gearscan.Screen.Optimize -> "Optimize"
        e7.gearscan.Screen.Heroes -> "Heroes"
        e7.gearscan.Screen.ScanData -> "Scan & data"
        e7.gearscan.Screen.Capture -> "Scan"
        e7.gearscan.Screen.Results -> "Best 50"
        e7.gearscan.Screen.ScanResult -> "Imported"
        else -> "Back"
    }
    BackLink(label) { model.back() }
}

@Composable
fun BackLink(text: String, onClick: () -> Unit) {
    Text("‹ $text", style = zen(14.sp, color = C.blue).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier.tap(onClick).padding(vertical = 2.dp))
}

@Composable
fun LinkText(text: String, size: Int = 12, color: Color = C.blue, onClick: () -> Unit) {
    Text(text, style = zen(size.sp, color = color).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier.tap(onClick))
}

// private: an arrow drawn only when a row opens something moves its neighbours; rows get one through ChevronSlot
@Composable
private fun Chevron(color: Color = C.muted) = Text("›", style = dela(16.sp, color))

/** Test tags the screen audit checks layouts by: rows of a list share their slots, cells of a grid row share a height. */
const val ROW_TAG = "list-row"
const val SLOT_TAG = "row-slot"
const val CELL_TAG = "grid-cell"

/** The arrow's place at a row's end, laid out whether or not the row opens anything. */
@Composable
fun ChevronSlot(shown: Boolean, color: Color = C.muted) {
    Box(Modifier.width(14.dp).testTag(SLOT_TAG), contentAlignment = Alignment.CenterEnd) { if (shown) Chevron(color) }
}

/** A fixed-width trailing cell; put it in every row of a list, empty where there's nothing to show. */
@Composable
fun Slot(width: Dp, content: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.width(width).testTag(SLOT_TAG), contentAlignment = Alignment.CenterEnd, content = content)
}

/**
 * A list row: optional [leading] (a face), the body, fixed-width [trailing] slots, and the chevron slot. Rows of one list
 * line up whatever each shows; one that opens nothing keeps the arrow's place empty.
 */
@Composable
fun ListRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
            trailing: @Composable RowScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().testTag(ROW_TAG).tap(onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = body)
        trailing()
        ChevronSlot(onClick != null)
    }
}

/** Cards in [columns] columns: each row as tall as its tallest card, short rows padded, so the grid never goes ragged. */
@Composable
fun <T> CardGrid(items: List<T>, columns: Int = 2, gap: Dp = 8.dp, cell: @Composable (item: T, modifier: Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (it in row) cell(it, Modifier.weight(1f).fillMaxHeight().testTag(CELL_TAG))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A bordered row: muted label, content, chevron. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowCard(label: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(C.card).border(2.dp, C.ink, shape).tap(onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = zen(12.sp, color = C.muted), modifier = Modifier.widthIn(min = 58.dp), maxLines = 1, softWrap = false)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content)
        ChevronSlot(onClick != null)
    }
}

@Composable
fun StatIcon(stat: Stat, size: Dp = 12.dp, tint: Color = C.ink) {
    AsyncImage(gameImage("icons/stats/${stat.icon}.png"), null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun StatValue(stat: Stat, text: String, color: Color = C.ink, size: Int = 12) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StatIcon(stat, 12.dp, color)
        FitText(text, zen(size.sp, color = color))
    }
}

/** Substats as the game lays them out: two columns of stat icon and value. 改 marks a mod: blue done in game, pink suggested. */
@Composable
fun SubGrid(item: Item, reforged: Boolean, size: Int = 11, color: Color = C.muted) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item.subs.indices.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { i ->
                    val s = item.subs[i]
                    val tint = when { item.mod?.index == i -> C.deepPink; s.modified -> C.blue; else -> color }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        StatIcon(s.type.stat, (size - 1).dp, tint)
                        FitText(s.type.format(e7.gearscan.calc.Calc.subValue(item, i, reforged)) + if (tint != color) " 改" else "",
                            zen(size.sp, if (tint == C.deepPink) FontWeight.Black else FontWeight.Bold, tint))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SetIcon(icon: String?, size: Dp = 20.dp) {
    if (icon == null) Box(Modifier.size(size)) else AsyncImage(gameImage("icons/sets/$icon.png"), null, Modifier.size(size))
}

@Composable
fun Face(url: String?, size: Dp, ring: Color = C.pink, ringWidth: Dp = 3.dp) {
    Box(Modifier.size(size).clip(CircleShape).stripes(C.stripeA, C.stripeB).border(ringWidth, ring, CircleShape),
        contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.size(size - ringWidth * 2).clip(CircleShape), contentScale = ContentScale.Crop)
    }
}

fun rankColor(rank: String) = when (rank) {
    "Epic" -> C.red
    "Heroic" -> C.purple
    "Rare" -> C.blue
    else -> C.muted
}


@Composable
fun GearTile(item: Item?, size: Dp = 44.dp, slotIcon: String? = null, setIcon: String? = null, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    val ghost = item?.ghost == true
    val inset = with(LocalDensity.current) { 4.dp.toPx() }
    val icon = slotIcon ?: item?.slot?.icon
    Box(Modifier.size(size).tap(onClick)) {
        Box(
            Modifier.size(size).clip(shape)
                .then(
                    when {
                        item == null -> Modifier.background(C.line).dashedBorder(C.faint, 2.dp, 10.dp)
                        ghost -> Modifier.stripes(C.ghostStripe, C.ghostBg, shape = shape).dashedBorder(C.blue, 2.dp, 10.dp)
                        else -> Modifier.background(C.slate).drawBehind {
                            drawRect(rankColor(item.rank), topLeft = Offset(0f, this.size.height - inset), size = Size(this.size.width, inset))
                        }.border(2.dp, C.ink, shape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) GearIcon(icon, size * 0.6f, when { item == null -> C.faint; ghost -> C.blue; else -> C.card })
        }
        if (item != null && size >= 36.dp) {
            Text("+${item.enhance}", style = dela(9.sp, C.card), modifier = Modifier.offset((-4).dp, (-6).dp)
                .clip(RoundedCornerShape(5.dp)).background(if (ghost) C.blue else C.ink).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        if (item != null) {
            if (setIcon != null) Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp)) { SetIcon(setIcon, size * 0.38f) }
        }
    }
}

/** The game's own slot glyphs (cm_icon_par*), upscaled as masks so they tint like the stat icons. */
@Composable
fun GearIcon(slotIcon: String, size: Dp, tint: Color) {
    AsyncImage(gameImage("gear/gear_$slotIcon.png"), null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun Skeleton(modifier: Modifier, radius: Dp = 6.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(C.line))
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.width(38.dp).height(24.dp).clip(shape).background(if (on) C.pink else C.card).border(2.dp, C.ink, shape)
        .tap { onChange(!on) }.padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(15.dp).clip(CircleShape).background(C.card).border(2.dp, C.ink, CircleShape))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, height: Dp = 36.dp, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(modifier.fillMaxWidth().height(height).clip(shape).background(C.card).border(2.dp, C.ink, shape)) {
        options.forEachIndexed { i, o ->
            if (i > 0) Box(Modifier.width(2.dp).fillMaxHeightCompat().background(C.ink))
            Box(Modifier.weight(1f).fillMaxHeightCompat().background(if (i == selected) C.pink else Color.Transparent).tap { onSelect(i) },
                contentAlignment = Alignment.Center) {
                Text(o, style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun Modifier.fillMaxHeightCompat() = this.then(Modifier.fillMaxHeight())

@Composable
fun Checkbox(on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(Modifier.size(20.dp).clip(shape).background(if (on) C.pink else C.card).border(2.dp, C.ink, shape).tap { onChange(!on) },
        contentAlignment = Alignment.Center) {
        if (on) CheckMark(11.dp)
    }
}

/** Drawn rather than a font glyph, so it stays inside its box at any font size. */
@Composable
fun CheckMark(size: Dp, color: Color = C.ink) {
    Canvas(Modifier.size(size)) {
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(this@Canvas.size.width * 0.08f, this@Canvas.size.height * 0.55f)
            lineTo(this@Canvas.size.width * 0.4f, this@Canvas.size.height * 0.85f)
            lineTo(this@Canvas.size.width * 0.95f, this@Canvas.size.height * 0.15f)
        }, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun WeightBoxes(weight: Int, pink: Boolean, onChange: ((Int) -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..3) {
            val shape = RoundedCornerShape(2.dp)
            Box(Modifier.size(11.dp).clip(shape).background(if (i <= weight) (if (pink) C.pink else C.ink) else C.card)
                .border(2.dp, C.ink, shape).tap(onChange?.let { f -> { f(if (weight == i) i - 1 else i) } }))
        }
    }
}

@Composable
fun NumberedStep(n: Int) {
    Box(Modifier.size(24.dp).clip(CircleShape).border(2.5.dp, C.deepPink, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", style = dela(12.sp))
    }
}

@Composable
fun DashedBox(modifier: Modifier = Modifier, color: Color = C.dash, bg: Color = Color.Transparent, radius: Dp = 12.dp,
              width: Dp = 2.dp, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(radius)).background(bg).dashedBorder(color, width, radius).tap(onClick).padding(padding),
        content = content)
}

@Composable
fun Hanko(char: String, size: Dp = 46.dp) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(size).clip(shape).border(3.dp, C.pink, shape).padding(4.dp).border(1.5.dp, C.pink, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center) {
        Text(char, style = zen((size.value * 0.5f).sp, FontWeight.Black, C.pink))
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        drawCircle(C.ink, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
    }
}

/** Fixed-size slot, so a name never shifts while the icon loads or when there is no artifact. */
@Composable
fun ArtifactIcon(model: e7.gearscan.AppModel, art: e7.gearscan.data.Artifact?, size: Dp) {
    // cut from the game's icon sheet onto the content site; the official site, which lacks the newest and 3★ ones, is the backup
    Box(Modifier.size(size)) {
        art?.let {
            var backup by androidx.compose.runtime.remember(it.code) { androidx.compose.runtime.mutableStateOf(false) }
            AsyncImage(if (backup) model.content.artifactIcon(it.code) else gameImage("artifacts/${it.code}.webp"), null, Modifier.size(size),
                error = MissingImage, onError = { if (!backup) backup = true })
        }
    }
}
