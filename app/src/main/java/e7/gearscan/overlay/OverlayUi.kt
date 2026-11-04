package e7.gearscan.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.data.fmtInt
import e7.gearscan.data.fmtNum
import e7.gearscan.features.tracker.ChecklistFeature
import e7.gearscan.ui.C
import e7.gearscan.ui.Stamp
import e7.gearscan.ui.hatch
import e7.gearscan.ui.CheckMark
import e7.gearscan.ui.FitText
import e7.gearscan.ui.Face
import e7.gearscan.ui.GearTile
import e7.gearscan.ui.SetIcon
import e7.gearscan.ui.StatIcon
import e7.gearscan.ui.StatStack
import e7.gearscan.ui.dela
import e7.gearscan.ui.hardShadow
import e7.gearscan.ui.tap
import e7.gearscan.ui.zen
import kotlin.math.abs

/** Moves the overlay window by raw screen deltas; window-local deltas would shift as the window moves. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.dragWindow(onMove: (Float, Float) -> Unit, onTap: (() -> Unit)? = null, onRelease: (() -> Unit)? = null): Modifier = composed {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; downX = e.rawX; downY = e.rawY; moved = false; true }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downX) + abs(e.rawY - downY) > 16) moved = true
                if (moved) onMove(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX; lastY = e.rawY
                true
            }
            MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke() else onRelease?.invoke(); true }
            MotionEvent.ACTION_CANCEL -> { onRelease?.invoke(); true }
            else -> false
        }
    }
}

/** The bubble's gesture: a tap opens it; a drag hands screen deltas and the release speed to [OverlayWindow.bubbleDrag]. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.dragBubble(w: OverlayWindow, onTap: () -> Unit): Modifier = composed {
    val slop = LocalViewConfiguration.current.touchSlop
    // screen positions: the window follows the finger, so positions in it (and VelocityTracker's speed) barely change
    val g = remember { object { var down = Offset.Zero; var last = Offset.Zero; var moved = false; val trail = ArrayDeque<Pair<Long, Offset>>() } }
    pointerInteropFilter { e ->
        val raw = Offset(e.rawX, e.rawY)
        val drag = w.bubbleDrag
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { g.down = raw; g.last = raw; g.moved = false; g.trail.clear(); g.trail.add(e.eventTime to raw); true }
            MotionEvent.ACTION_MOVE -> {
                if (!g.moved && (raw - g.down).getDistance() > slop) { g.moved = true; drag?.start() }
                if (g.moved) drag?.move(raw.x - g.last.x, raw.y - g.last.y)
                g.last = raw
                g.trail.add(e.eventTime to raw)
                while (g.trail.size > 2 && e.eventTime - g.trail.first().first > 80) g.trail.removeFirst()
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!g.moved) onTap() else releaseSpeed(g.trail, e.eventTime).let { drag?.end(it.x, it.y) }
                true
            }
            MotionEvent.ACTION_CANCEL -> { if (g.moved) drag?.end(0f, 0f); true }
            else -> false
        }
    }
}

/** px/s over the last moves; none when the finger rested before letting go. */
private fun releaseSpeed(trail: ArrayDeque<Pair<Long, Offset>>, upAt: Long): Offset {
    val (t1, p1) = trail.lastOrNull() ?: return Offset.Zero
    val (t0, p0) = trail.first()
    if (upAt - t1 > 50 || t1 <= t0) return Offset.Zero
    return (p1 - p0) * (1000f / (t1 - t0))
}

/** Opens with a short spring out of the bubble's side, as the phone's own bubbles do. */
internal fun Modifier.popIn(w: OverlayWindow): Modifier = composed {
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, spring(0.8f, Spring.StiffnessMediumLow)) }
    val origin = TransformOrigin(if (w.bubbleLeft) 0f else 1f, 0f)
    graphicsLayer { alpha = k.value.coerceIn(0f, 1f); scaleX = 0.85f + 0.15f * k.value; scaleY = scaleX; transformOrigin = origin }
}

/** The panel's two parts as one column; the service shows them as two windows (see [OverlayControls]), and the hint and dismiss target as two more. */
@Composable
fun OverlayUi(g: AppGraph, onMove: (Float, Float) -> Unit, onDock: (Boolean) -> Unit, onClose: () -> Unit) {
    if (!g.overlay.expanded) Box(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) { BubbleHint(g); OverlayTop(g, onMove, onDock, onClose) }
        Box(Modifier.align(Alignment.BottomCenter)) { DismissTarget(g.overlay) }
    }
    else if (g.tracker.shown) OverlayTop(g, onMove, onDock, onClose)
    else Column(Modifier.width(PANEL_WIDTH.dp), verticalArrangement = Arrangement.spacedBy(PANEL_GAP.dp)) {
        OverlayTop(g, onMove, onDock, onClose)
        OverlayBody(g)
    }
}

/** The touchable window: the bubble, or the panel's controls. */
@Composable
fun OverlayTop(g: AppGraph, onMove: (Float, Float) -> Unit, onDock: (Boolean) -> Unit, onClose: () -> Unit) {
    if (g.tracker.shown) { UpgradeOverlay(g, onMove, onDock, onClose); return }
    val b = g.checklist.build ?: return
    val w = g.overlay
    if (!w.expanded) Bubble(g, b) { w.expanded = true; onDock(true) }
    else OverlayControls(g, b, onMove, { w.expanded = false; onDock(false) }, onClose)
}

const val PANEL_WIDTH = 330

/** The tracker over the game is a rail about as wide as the game's left bar (landscape), starting under its title row. */
const val RAIL_WIDTH = 94
const val RAIL_TOP = 38
const val PANEL_GAP = 4
const val BUBBLE = 56
/** The bubble's window: the bubble with room for its shadow and badge. The hint's window is as tall, so the two line up. */
const val BUBBLE_BOX = 84
const val TARGET = 60
/** The dismiss target's window, with room for the target to grow when it holds the bubble. */
const val TARGET_BOX = 104
const val TARGET_MARGIN = 20
/** Taps pass through an app's overlay only at this opacity or less (Android 12+ blocks them above 0.8); 0 hides the list, keeping the controls. */
val OPACITY_STEPS = listOf(0.8f, 0.6f, 0.4f, 0f)

/** What a reforge step asks for; the values shown are the piece after it. */
private const val REFORGE_NOTE = "Reforge: Lv.85 → Lv.90"

/** Where the next step happens: in pool runs the hero to open in the game, otherwise the piece. */
private fun nextHint(b: OverlayBuild, i: Int, done: List<Boolean>): String {
    val p = b.pieces[i]
    val hero = p.forHero ?: return if (p.modStep) "${p.item.slot.label} · modify" else if (p.reforgeStep) "${p.item.slot.label} · reforge"
        else "${p.item.slot.label} · ${p.owner.removePrefix("on ").removePrefix("in ")}"
    if (p.modStep) return "Modify · $hero"
    if (p.reforgeStep) return "Reforge · $hero"
    val left = b.pieces.indices.count { b.pieces[it].forHero == hero && !b.pieces[it].otherMenu && !done.getOrElse(it) { true } }
    return "$hero · $left ${if (left == 1) "piece" else "pieces"}"
}

@Composable
private fun Bubble(g: AppGraph, b: OverlayBuild, onOpen: () -> Unit) {
    val doneCount = g.checklist.done.count { it }
    BubbleShell(g.overlay, doneCount.toFloat() / b.pieces.size.coerceAtLeast(1), C.pink, C.card, "$doneCount/${b.pieces.size}", onOpen) {
        Face(b.face, BUBBLE.dp, ring = C.card, ringWidth = 3.dp)
    }
}

/** The collapsed overlay, shaped like the phone's chat bubbles; the badge sits on the side away from the screen edge. */
@Composable
internal fun BubbleShell(s: OverlayWindow, progress: Float, ring: Color, track: Color, badge: String?, onTap: () -> Unit,
                         corner: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(0.55f, Spring.StiffnessMedium)) }
    val left by animateFloatAsState(if (s.closing) 0f else 1f, tween(200), label = "bubble-closing")
    Box(Modifier.size(BUBBLE_BOX.dp).dragBubble(s, onTap).graphicsLayer { val k = pop.value * left; scaleX = k; scaleY = k; alpha = left },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(BUBBLE.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(C.card).drawWithContent {
            drawContent()
            val w = 3.dp.toPx()
            val inset = Offset(w / 2, w / 2)
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, inset, arc, style = Stroke(w))
            if (progress > 0f) drawArc(ring, -90f, 360f * progress.coerceAtMost(1f), false, inset, arc, style = Stroke(w, cap = StrokeCap.Round))
        }, contentAlignment = Alignment.Center) { content() }
        // the tracked piece's set, low on the side away from the edge, so it can be matched in the game's inventory
        corner?.let {
            Box(Modifier.align(if (s.bubbleLeft) Alignment.BottomEnd else Alignment.BottomStart).padding(bottom = 6.dp, start = 4.dp, end = 4.dp).size(26.dp)
                .clip(CircleShape).background(C.card).border(1.5.dp, C.edge, CircleShape), contentAlignment = Alignment.Center) { it() }
        }
        badge?.let {
            Text(it, style = dela(10.sp, C.onAccent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(if (s.bubbleLeft) Alignment.TopEnd else Alignment.TopStart).widthIn(max = BUBBLE_BOX.dp).padding(top = 6.dp).clip(RoundedCornerShape(999.dp)).background(C.pink).border(1.5.dp, C.card, RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** The next step, beside the bubble for a few seconds; a window of its own that taps pass through to the game. */
@Composable
fun BubbleHint(g: AppGraph) {
    val s = g.checklist
    val w = g.overlay
    val b = s.build ?: return
    if (g.tracker.shown || w.expanded) return
    val doneCount = s.done.count { it }
    val nextAt = s.current.takeIf { it >= 0 }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(nextAt?.let { b.pieces[it].item.id }, doneCount) { shown = true; kotlinx.coroutines.delay(5000); shown = false }
    LaunchedEffect(w.dragging) { if (w.dragging) shown = false }
    // faded, not removed: a window that shrinks to nothing is never redrawn and keeps showing its last frame
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "hint")
    Box(Modifier.height(BUBBLE_BOX.dp).padding(horizontal = 10.dp).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) {
        Text(nextAt?.let { nextHint(b, it, s.done) } ?: "Tap to record", style = zen(12.sp, color = C.card), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp).shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(C.ink)
                .padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

/** Rises at the bottom while the bubble is dragged; it pulls the bubble in when close, and dropping it there closes the overlay. */
@Composable
fun DismissTarget(s: OverlayWindow) {
    val shown by animateFloatAsState(if (s.dragging) 1f else 0f, spring(0.8f, Spring.StiffnessMediumLow), label = "target")
    val held by animateFloatAsState(if (s.captured) 1f else 0f, spring(0.5f, Spring.StiffnessMedium), label = "target-held")
    Box(Modifier.size(TARGET_BOX.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(TARGET.dp).graphicsLayer {
            alpha = shown.coerceIn(0f, 1f); translationY = (1f - shown) * 32.dp.toPx(); scaleX = 1f + 0.3f * held; scaleY = scaleX
        }.shadow(6.dp, CircleShape).clip(CircleShape).background(lerp(C.ink, C.red, held)).border(2.dp, C.card, CircleShape),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                val w = 3.dp.toPx()
                drawLine(C.card, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                drawLine(C.card, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }
}

/** Everything the player taps: title, tabs, the step buttons and opacity. Solid, since taps land here. */
@Composable
private fun OverlayControls(g: AppGraph, b: OverlayBuild, onMove: (Float, Float) -> Unit, onCollapse: () -> Unit, onClose: () -> Unit) {
    val s = g.checklist
    val w = g.overlay
    LaunchedEffect(s.recorded) { if (s.recorded) { kotlinx.coroutines.delay(1500); onClose() } }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.popIn(w).padding(end = 4.dp).width(PANEL_WIDTH.dp).hardShadow(C.pink, 4.dp, 4.dp, 18.dp).clip(shape).background(C.card)
        .border(2.5.dp, C.edge, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.dragWindow(onMove).padding(horizontal = 2.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) { Box(Modifier.width(16.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(C.muted)) }
            }
            Box(Modifier.dragWindow(onMove)) { Face(b.face, 26.dp) }
            Text(b.title, style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).dragWindow(onMove))
            Row(Modifier.clip(RoundedCornerShape(999.dp)).border(2.dp, C.edge, RoundedCornerShape(999.dp))) {
                listOf("Pieces", "Stats").forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.width(2.dp).height(22.dp).background(C.edge))
                    Text(t, style = zen(11.sp, color = if (w.tab == i) C.onAccent else C.ink), modifier = Modifier.background(if (w.tab == i) C.pink else C.card).tap { w.tab = i }
                        .padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
            Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, C.edge, CircleShape).tap(onCollapse), contentAlignment = Alignment.Center) {
                Text("–", style = dela(12.sp))
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(C.ink).tap(onClose), contentAlignment = Alignment.Center) {
                Text("×", style = dela(13.sp, C.card))
            }
        }
        b.flag?.let { Text(it, style = zen(11.sp, FontWeight.Medium, C.deepPink), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = s.done.count { it }
            FitText("$n of ${b.pieces.size} done", zen(12.sp, color = C.muted), Modifier.weight(1f), short = "$n/${b.pieces.size}")
            // the list below lets taps through to the game; its opacity is the one thing worth changing mid-game (not in the phone's own window)
            if (!w.native) Row(Modifier.width(64.dp).clip(RoundedCornerShape(999.dp)).border(2.dp, C.edge, RoundedCornerShape(999.dp)).tap {
                w.opacity = OPACITY_STEPS[(OPACITY_STEPS.indexOf(w.opacity) + 1).mod(OPACITY_STEPS.size)]
            }.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to C.ink, 0.5f to C.ink, 0.5f to C.card, 1f to C.card)).border(1.5.dp, C.edge, CircleShape))
                FitText(if (w.listHidden) "Hidden" else "${Math.round(w.opacity * 100)}%", zen(11.sp))
            }
            val last = s.lastDone
            Text("Undo", style = zen(11.sp, color = if (last != null) C.ink else C.faint), modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .border(2.dp, if (last != null) C.edge else C.faint, RoundedCornerShape(999.dp))
                .tap(if (last != null && !s.recorded) ({ s.undo() }) else null).padding(horizontal = 8.dp, vertical = 3.dp))
            when {
                s.recorded -> Text("Recorded", style = zen(12.sp, color = C.blue))
                // the game's gear changed; the last step tells the app, or its next search starts from old gear
                n == b.pieces.size -> FooterButton("Finished · record it") { s.finish() }
                else -> FooterButton("Done · next") { s.markCurrentDone() }
            }
        }
        // the game rolls a modification's value; the box gets what was rolled. Kept for the whole checklist, so steps don't move the list
        if (b.pieces.any { it.modStep }) RolledRow(s, b, s.current.takeIf { it >= 0 && b.pieces[it].modStep })
        // with the list hidden, the open step is named here instead
        s.current.takeIf { it >= 0 && w.listHidden }?.let { b.pieces[it] }?.let { p ->
            Text((p.forHero?.let { "$it · " } ?: "") + "${p.item.slot.label} · ${p.mainText} · " + (p.mod?.let { "改 Mod: $it" } ?: REFORGE_NOTE.takeIf { p.reforgeStep } ?: p.owner),
                style = zen(12.sp, color = if (p.otherMenu) C.deepPink else C.ink), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun RolledRow(s: ChecklistFeature, b: OverlayBuild, at: Int?) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val m = at?.let { b.pieces[it].item.mod }
        if (at == null || m == null) {
            FitText("改 Mod steps ask what the game rolled", zen(11.sp, color = C.muted), Modifier.weight(1f))
            return@Row
        }
        val rolls = b.pieces[at].orig.subs.getOrNull(m.index)?.rolls ?: 1
        val range = e7.gearscan.opt.Mods.range(m.to, rolls)
        val v = s.rolledValue(at) ?: m.toValue
        fun set(x: Double) = s.setRolled(at, x.coerceIn(range.first.toDouble(), range.last.toDouble()))
        FitText("改 Rolled ${m.to.short}", zen(12.sp, color = C.deepPink), Modifier.weight(1f))
        val big = range.last - range.first > 40
        if (big) RollStep("−10", "Rolled value down 10") { set(v - 10) }
        RollStep("−", "Rolled value down") { set(v - 1) }
        FitText(m.to.format(v), dela(14.sp, C.blue), Modifier.width(52.dp), textAlign = TextAlign.Center)
        RollStep("+", "Rolled value up") { set(v + 1) }
        if (big) RollStep("+10", "Rolled value up 10") { set(v + 10) }
    }
}

@Composable
private fun RollStep(text: String, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.height(24.dp).widthIn(min = 30.dp).clip(shape).border(2.dp, C.edge, shape).tap(onClick)
        .semantics { contentDescription = label }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, style = dela(11.sp), maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** What the panel shows: the pieces or the stats. Drawn in a window that taps pass through to the game. */
@Composable
fun OverlayBody(g: AppGraph) {
    val s = g.checklist
    val w = g.overlay
    val b = s.build ?: return
    if (g.tracker.shown || !w.expanded || w.listHidden) return
    val shape = RoundedCornerShape(18.dp)
    val scroll = rememberScrollState()
    val rowTops = remember { mutableMapOf<Int, Int>() }
    // nobody can scroll a window that taps pass through, so the open piece is scrolled into view for them
    LaunchedEffect(s.current, w.tab) { rowTops[s.current]?.let { scroll.animateScrollTo(it) } }
    val max = (LocalConfiguration.current.screenHeightDp - 110).coerceAtLeast(160)
    Column(Modifier.popIn(w).padding(end = 4.dp).width(PANEL_WIDTH.dp).heightIn(max = max.dp).clip(shape).background(C.card)
        .border(2.5.dp, C.edge, shape).verticalScroll(scroll)) {
        if (w.tab == 0) b.pieces.forEachIndexed { i, p ->
            // a heading each time the hero (pool checklists) or the menu (equip, reforge, modify) changes, so 48 moves read as a few short lists
            fun group(q: OverlayPiece?) = q?.let { it.forHero to it.menu }
            if ((p.forHero != null || p.otherMenu) && group(b.pieces.getOrNull(i - 1)) != group(p)) {
                val members = b.pieces.indices.filter { group(b.pieces[it]) == group(p) }
                val done = members.count { s.done.getOrElse(it) { false } }
                Row(Modifier.fillMaxWidth().background(C.paper).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when { p.modStep -> "改 Modify" + (p.forHero?.let { " · $it" } ?: ""); p.reforgeStep -> "Reforge" + (p.forHero?.let { " · $it" } ?: "")
                        else -> p.forHero.orEmpty() }, style = dela(12.sp),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$done of ${members.size}", style = zen(11.sp, color = C.muted))
                }
            }
            Column(Modifier.onGloballyPositioned { rowTops[i] = it.positionInParent().y.toInt() }) { PieceRow(s, i, p, i == s.current) }
        }
        else StatsTable(b)
    }
}

@Composable
private fun FooterButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(text, style = zen(12.sp, color = C.onAccent), modifier = Modifier.hardShadow(C.shadow, 2.dp, 2.dp, 14.dp).clip(shape).background(C.pink)
        .border(2.dp, C.edge, shape).tap(onClick).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun Check(on: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(22.dp).clip(shape).background(if (on) C.pink else C.card).border(2.5.dp, C.edge, shape),
        contentAlignment = Alignment.Center) { if (on) CheckMark(12.dp, C.onAccent) }
}

@Composable
private fun PieceRow(s: ChecklistFeature, i: Int, p: OverlayPiece, open: Boolean) {
    val done = s.done.getOrElse(i) { false }
    val worn = p.owner.startsWith("on ") && !p.onHero
    val ownerColor = if (worn) C.blue else C.muted
    if (open) {
        Row(Modifier.fillMaxWidth().hatch().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Check(done)
            GearTile(p.item, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetIcon(p.setIcon, 18.dp)
                    Text((p.forHero?.let { "$it · " } ?: "") + p.item.slot.label, style = dela(13.sp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatIcon(p.item.mainType.stat, 12.dp)
                    Text(p.mainText, style = zen(13.sp))
                }
                StatStack(p.orig, reforged = p.reforged, main = false)
                p.mod?.let { Text("改 Mod: $it", style = zen(11.sp, color = C.deepPink)) }
                if (p.reforgeStep) Text(REFORGE_NOTE, style = zen(11.sp, color = C.deepPink))
                // whoever wears the piece is where the game shows it, so the name is never cut
                if (!p.otherMenu) Text(p.owner.replaceFirstChar { it.uppercase() }, style = zen(12.sp, color = ownerColor),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.card).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Check(done)
            GearTile(p.item, 24.dp)
            SetIcon(p.setIcon, 16.dp)
            Text("${p.item.slot.label} · ${p.mainText}", style = zen(12.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // the stamp's room is kept when not done, so ticking a piece doesn't push the owner sideways
            Box(Modifier.alpha(if (done) 1f else 0f)) { Stamp("済") }
            if (!p.otherMenu && !worn) Text(p.owner, style = zen(11.sp, color = ownerColor))
        }
        // a wearer's name gets a line of its own, so it is never cut
        val note = p.mod?.let { "改 Mod: $it" } ?: REFORGE_NOTE.takeIf { p.reforgeStep } ?: p.owner.takeIf { worn }
        note?.let { Text(it, style = zen(11.sp, color = if (p.otherMenu) C.deepPink else ownerColor),
            modifier = Modifier.padding(start = 98.dp, end = 10.dp, bottom = 3.dp)) }
    }
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
}

@Composable
private fun StatsTable(b: OverlayBuild) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val hs = zen(9.sp, color = C.muted).copy(letterSpacing = 0.5.sp)
            Text("STAT", style = hs, modifier = Modifier.padding(start = 18.dp).weight(1f))
            FitText("BUILD", hs, Modifier.width(58.dp), textAlign = TextAlign.End)
            FitText("NOW", hs, Modifier.width(52.dp), textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.edge))
        b.stats.forEach { (st, build, now) ->
            fun f(v: Double) = if (st.pct) "${fmtNum(Math.floor(v))}%" else fmtInt(Math.floor(v))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                StatIcon(st, 12.dp)
                Text(st.label, style = zen(12.sp), modifier = Modifier.padding(start = 6.dp).weight(1f))
                FitText(f(build), dela(12.sp), Modifier.width(58.dp), textAlign = TextAlign.End)
                FitText(f(now), zen(11.sp, FontWeight.Medium, C.muted), Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
        Text("Compare with the hero's stats in the game once every piece is on.", style = zen(10.sp, FontWeight.Medium, C.muted),
            modifier = Modifier.padding(top = 6.dp))
    }
}
