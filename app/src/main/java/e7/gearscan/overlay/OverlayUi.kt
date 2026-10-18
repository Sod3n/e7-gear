package e7.gearscan.overlay

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.data.fmtInt
import e7.gearscan.data.fmtNum
import e7.gearscan.ui.C
import e7.gearscan.ui.Stamp
import e7.gearscan.ui.hatch
import e7.gearscan.ui.CheckMark
import e7.gearscan.ui.FitText
import e7.gearscan.ui.Face
import e7.gearscan.ui.GearTile
import e7.gearscan.ui.SetIcon
import e7.gearscan.ui.StatIcon
import e7.gearscan.ui.SubGrid
import e7.gearscan.ui.dela
import e7.gearscan.ui.hardShadow
import e7.gearscan.ui.tap
import e7.gearscan.ui.zen
import kotlin.math.abs

/** Moves the overlay window by raw screen deltas; window-local deltas would shift as the window moves. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.dragWindow(onMove: (Float, Float) -> Unit, onTap: (() -> Unit)? = null, onRelease: (() -> Unit)? = null): Modifier = composed {
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

@Composable
fun OverlayUi(onMove: (Float, Float) -> Unit, onDock: (Boolean) -> Unit, onClose: () -> Unit) {
    val b = OverlayState.build ?: return
    if (!OverlayState.expanded) Bubble(b, onMove, onRelease = { if (OverlayState.closeZone) { OverlayState.closeZone = false; onClose() } }) {
        OverlayState.expanded = true; onDock(true)
    }
    else Panel(b, onMove, { OverlayState.expanded = false; onDock(false) }, onClose)
}

@Composable
private fun Bubble(b: OverlayBuild, onMove: (Float, Float) -> Unit, onRelease: () -> Unit, onOpen: () -> Unit) {
    val s = OverlayState
    val doneCount = s.done.count { it }
    val next = s.current.takeIf { it >= 0 }?.let { b.pieces[it] }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp, bottom = 8.dp)) {
        val closing = s.closeZone
        Text(when {
            closing -> "Release to close"
            next != null -> "Next: ${next.item.slot.label} ${next.owner.replace("on ", "from ").replace("in bag", "from bag")}"
            else -> "All 6 done · check stats"
        }, style = zen(12.sp, color = C.card), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp).clip(RoundedCornerShape(10.dp)).background(if (closing) C.red else C.ink).padding(horizontal = 10.dp, vertical = 6.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.dragWindow(onMove, onOpen, onRelease).padding(start = 8.dp, bottom = 6.dp)) {
            Box(Modifier.size(64.dp).hardShadow(if (closing) C.red else C.pink, 3.dp, 4.dp, 32.dp).clip(CircleShape)
                .background(if (closing) C.red else C.card).border(3.dp, C.ink, CircleShape),
                contentAlignment = Alignment.Center) { if (closing) Text("×", style = dela(28.sp, C.card)) else Face(b.face, 48.dp) }
            Text("$doneCount/${b.pieces.size}", style = dela(11.sp), modifier = Modifier.align(Alignment.BottomStart).offset((-8).dp, 6.dp)
                .clip(RoundedCornerShape(999.dp)).background(C.pink).border(2.dp, C.ink, RoundedCornerShape(999.dp))
                .padding(horizontal = 7.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun Panel(b: OverlayBuild, onMove: (Float, Float) -> Unit, onCollapse: () -> Unit, onClose: () -> Unit) {
    val s = OverlayState
    val h = (LocalConfiguration.current.screenHeightDp - 12).coerceIn(240, 440)
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.padding(end = 4.dp, bottom = 4.dp).width(330.dp).height(h.dp).alpha(s.opacity)
        .hardShadow(C.pink, 4.dp, 4.dp, 18.dp).clip(shape).background(C.card.copy(alpha = 0.97f)).border(2.5.dp, C.ink, shape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.dragWindow(onMove).padding(horizontal = 2.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) { Box(Modifier.width(16.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(C.muted)) }
            }
            Box(Modifier.dragWindow(onMove)) { Face(b.face, 26.dp) }
            Text(b.title, style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).dragWindow(onMove))
            Row(Modifier.clip(RoundedCornerShape(999.dp)).border(2.dp, C.ink, RoundedCornerShape(999.dp))) {
                listOf("Pieces", "Stats").forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.width(2.dp).height(22.dp).background(C.ink))
                    Text(t, style = zen(11.sp), modifier = Modifier.background(if (s.tab == i) C.pink else C.card).tap { s.tab = i }
                        .padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
            Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, C.ink, CircleShape).tap(onCollapse), contentAlignment = Alignment.Center) {
                Text("–", style = dela(12.sp))
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(C.ink).tap(onClose), contentAlignment = Alignment.Center) {
                Text("×", style = dela(13.sp, C.card))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.ink))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (s.tab == 0) b.pieces.forEachIndexed { i, p ->
                // pool checklists: a heading each time the hero changes, so 48 moves read as a few short lists
                if (p.forHero != null && b.pieces.getOrNull(i - 1)?.forHero != p.forHero) {
                    val n = b.pieces.count { it.forHero == p.forHero }
                    val done = b.pieces.indices.count { b.pieces[it].forHero == p.forHero && s.done.getOrElse(it) { false } }
                    Row(Modifier.fillMaxWidth().background(C.paper).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.forHero, style = dela(12.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$done of $n", style = zen(11.sp, color = C.muted))
                    }
                }
                PieceRow(i, p, i == s.current)
            }
            else StatsTable(b)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.ink))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.tab == 0) {
                val n = s.done.count { it }
                Text("$n of ${b.pieces.size} done", style = zen(12.sp, color = C.muted), modifier = Modifier.weight(1f))
                val all = n == b.pieces.size
                FooterButton(if (all) "Check stats" else "Done · next") {
                    if (all) s.tab = 1 else s.current.takeIf { it >= 0 }?.let { s.done[it] = true; s.focus = -1 }
                }
            } else {
                Text("Opacity", style = zen(10.sp, color = C.muted))
                OpacitySlider(Modifier.weight(1f))
                Text("${Math.round(s.opacity * 100)}%", style = zen(10.sp, color = C.muted))
                Text("Close", style = zen(11.sp, color = C.deepPink), modifier = Modifier.tap(onClose).padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun FooterButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(text, style = zen(12.sp), modifier = Modifier.hardShadow(C.ink, 2.dp, 2.dp, 14.dp).clip(shape).background(C.pink)
        .border(2.dp, C.ink, shape).tap(onClick).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun Check(on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(22.dp).clip(shape).background(if (on) C.pink else C.card).border(2.5.dp, C.ink, shape).tap(onToggle),
        contentAlignment = Alignment.Center) { if (on) CheckMark(12.dp) }
}

@Composable
private fun PieceRow(i: Int, p: OverlayPiece, open: Boolean) {
    val s = OverlayState
    val done = s.done.getOrElse(i) { false }
    val ownerColor = if (p.owner.startsWith("on ") && !p.onHero) C.blue else C.muted
    if (open) {
        Row(Modifier.fillMaxWidth().hatch().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Check(done) { s.done[i] = !done }
            GearTile(p.item, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetIcon(p.setIcon, 18.dp)
                    Text((p.forHero?.let { "$it · " } ?: "") + p.item.slot.label, style = dela(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text(p.owner, style = zen(11.sp, color = ownerColor).copy(lineHeight = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatIcon(p.item.mainType.stat, 12.dp)
                    Text(p.mainText, style = zen(13.sp))
                }
                SubGrid(p.orig, p.reforged)
                p.mod?.let { Text("改 Mod: $it", style = zen(11.sp, color = C.deepPink), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("Find: ${p.find}", style = zen(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.card).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().tap { s.focus = i }.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Check(done) { s.done[i] = !done }
            GearTile(p.item, 24.dp)
            SetIcon(p.setIcon, 16.dp)
            Text((p.forHero?.let { "$it: " } ?: "") + "${p.item.slot.label} · ${p.mainText}", style = zen(12.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.mod != null) Text("改", style = dela(11.sp, C.deepPink))
            // the stamp's room is kept when not done, so ticking a piece doesn't push the owner sideways
            Box(Modifier.alpha(if (done) 1f else 0f)) { Stamp("済") }
            Text(p.owner, style = zen(11.sp, color = ownerColor).copy(lineHeight = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
}

@Composable
private fun StatsTable(b: OverlayBuild) {
    val s = OverlayState
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val hs = zen(9.sp, color = C.muted).copy(letterSpacing = 0.5.sp)
            Text("STAT", style = hs, modifier = Modifier.padding(start = 18.dp).weight(1f))
            FitText("BUILD", hs, Modifier.width(58.dp), textAlign = TextAlign.End)
            FitText("NOW", hs, Modifier.width(52.dp), textAlign = TextAlign.End)
            Spacer(Modifier.width(30.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.ink))
        b.stats.forEachIndexed { i, (st, build, now) ->
            fun f(v: Double) = if (st.pct) "${fmtNum(Math.floor(v))}%" else fmtInt(Math.floor(v))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                StatIcon(st, 12.dp)
                Text(st.label, style = zen(12.sp), modifier = Modifier.padding(start = 6.dp).weight(1f))
                FitText(f(build), dela(12.sp), Modifier.width(58.dp), textAlign = TextAlign.End)
                FitText(f(now), zen(11.sp, FontWeight.Medium, C.muted), Modifier.width(52.dp), textAlign = TextAlign.End)
                Spacer(Modifier.width(8.dp))
                Check(s.statOk.getOrElse(i) { false }) { s.statOk[i] = !s.statOk[i] }
            }
        }
        Text("Tick each stat once the game shows the build value.", style = zen(10.sp, FontWeight.Medium, C.muted),
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun OpacitySlider(modifier: Modifier) {
    val s = OverlayState
    var w by remember { mutableFloatStateOf(1f) }
    fun set(x: Float) { s.opacity = (0.3f + 0.7f * (x / w).coerceIn(0f, 1f)) }
    Box(modifier.height(18.dp).onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    e.changes.firstOrNull()?.let { c -> if (c.pressed) { set(c.position.x); c.consume() } }
                }
            }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).border(2.dp, C.ink, RoundedCornerShape(99.dp)))
        Box(Modifier.fillMaxWidth((s.opacity - 0.3f) / 0.7f).height(8.dp).clip(RoundedCornerShape(99.dp)).background(C.pink)
            .border(2.dp, C.ink, RoundedCornerShape(99.dp)))
    }
}
