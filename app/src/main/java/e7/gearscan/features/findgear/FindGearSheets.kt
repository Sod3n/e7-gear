package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.data.*
import e7.gearscan.opt.EsForecast
import e7.gearscan.opt.GearBounds

/** Slider stops over a target's range: whole numbers, or a round step that keeps the slider under ~60 stops. */
private fun targetStep(min: Double, max: Double) = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0).first { it >= (max - min) / 60 }

@Composable
@OptIn(KeyboardInput::class)
internal fun PotentialTargetsSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Substat targets", button = "Done", onButton = { graph.nav.sheet = null }) {
        q.targets.forEach { (t, value) ->
            val limits = GearBounds.target(t, q.reforge, q.show == 2)
            val step = targetStep(limits.minimum, limits.maximum)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) {
                    ValueSlider(targetLabel(t), { StatIcon(t.stat, 18.dp) }, value.toDoubleOrNull() ?: limits.minimum, limits.minimum, limits.maximum, step,
                        { targetValue(t, fmtNum(it)) }) { v -> graph.findGear.edit(graph.findGear.query.let { it.copy(targets = it.targets + (t to fmtNum(v))) }) }
                }
                Box(Modifier.size(40.dp).tap { graph.findGear.edit(q.copy(targets = q.targets - t)) }.semantics { contentDescription = "Remove ${t.short}" },
                    contentAlignment = Alignment.Center) { Text("×", style = dela(18.sp, C.muted)) }
            }
        }
        if (q.targets.size < 4) {
            SectionTitle("Add a substat")
            CardGrid(GearStat.entries.filter { it !in q.targets }, columns = 3, gap = 7.dp) { t, m ->
                val limits = GearBounds.target(t, q.reforge, q.show == 2)
                val step = targetStep(limits.minimum, limits.maximum)
                val start = (kotlin.math.round((limits.minimum + (limits.maximum - limits.minimum) * .4) / step) * step).coerceIn(limits.minimum, limits.maximum)
                PotentialChoice(t.short, m, false, leading = { StatIcon(t.stat, 14.dp) }) { graph.findGear.edit(q.copy(targets = q.targets + (t to fmtNum(start)))) }
            }
        }
    }
}

@Composable
internal fun PotentialSlotsSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Equipment slots", button = "Done", onButton = { graph.nav.sheet = null }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("All", selected = q.slots == Slot.entries.toSet(), onClick = { graph.findGear.edit(q.copy(slots = Slot.entries.toSet())) })
            Pill("No boots", selected = q.slots == Slot.entries.toSet() - Slot.BOOTS, onClick = { graph.findGear.edit(q.copy(slots = Slot.entries.toSet() - Slot.BOOTS)) })
        }
        CardGrid(Slot.entries, columns = 3) { s, m ->
            val selected = s in q.slots
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (selected) C.lightPink else C.card).border(2.dp, if (selected) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { graph.findGear.edit(q.copy(slots = if (selected) q.slots - s else q.slots + s)) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GearIcon(s.icon, 30.dp, C.ink); VSpace(8.dp); FitText(s.label, zen(12.sp))
            }
        }
    }
}

@Composable
internal fun PotentialSetsSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Sets", trailing = { Pill("Any set", selected = q.sets.isEmpty(), onClick = { graph.findGear.edit(q.copy(sets = emptySet())) }) },
        button = "Done", onButton = { graph.nav.sheet = null }) {
        CardGrid(graph.content.content.sets, columns = 4, gap = 7.dp) { s, m ->
            val on = s.key in q.sets
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (on) C.lightPink else C.card).border(2.dp, if (on) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { graph.findGear.edit(q.copy(sets = if (on) q.sets - s.key else q.sets + s.key)) }.padding(horizontal = 4.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SetIcon(s.icon, 27.dp); VSpace(6.dp); FitText(s.name, zen(10.sp))
            }
        }
    }
}

@Composable
internal fun PotentialMainsSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Main stat", trailing = { Pill("Any", selected = q.mains.isEmpty(), onClick = { graph.findGear.edit(q.copy(mains = emptySet())) }) },
        button = "Done", onButton = { graph.nav.sheet = null }) {
        CardGrid(GearStat.entries, columns = 3) { t, m ->
            PotentialChoice(t.short, m, t in q.mains, leading = { StatIcon(t.stat, 14.dp) }) {
                graph.findGear.edit(q.copy(mains = if (t in q.mains) q.mains - t else q.mains + t))
            }
        }
    }
}

@Composable
internal fun PotentialFinishSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Finish", button = "Done", onButton = { graph.nav.sheet = null }) {
        listOf(false to "+15", true to "+15, reforged where possible").forEach { (reforge, label) ->
            PotentialChoice(label, Modifier.fillMaxWidth(), q.reforge == reforge) { graph.findGear.edit(q.copy(reforge = reforge)) }
        }
    }
}

@Composable
internal fun PotentialChanceSheet(graph: AppGraph) {
    val q = graph.findGear.query
    var i by remember(q) { mutableIntStateOf(chanceStop(q)) }
    val source = remember { MutableInteractionSource() }
    val active = source.collectIsDraggedAsState().value || source.collectIsPressedAsState().value
    SheetBody("Lowest chance", button = "Done", onButton = { graph.nav.sheet = null }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) C.lightPink else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 4.dp)) {
            FitText(chanceStopLabel(i), dela(20.sp, if (active) C.deepPink else C.blue))
            SliderTrack(i.toFloat(), { i = kotlin.math.round(it).toInt().coerceIn(CHANCE_STOPS.indices) }, { graph.findGear.edit(withChanceStop(graph.findGear.query, i)) },
                0f..(CHANCE_STOPS.size - 1).toFloat(), CHANCE_STOPS.size - 2, source, active, "Lowest chance shown", Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text("more pieces", style = zen(10.sp, color = C.muted), modifier = Modifier.weight(1f))
                Text("fewer pieces", style = zen(10.sp, color = C.muted))
            }
        }
    }
}

@Composable
internal fun PotentialPiecesSheet(graph: AppGraph) {
    val q = graph.findGear.query
    SheetBody("Pieces", button = "Done", onButton = { graph.nav.sheet = null }) {
        PIECES.forEachIndexed { i, label -> PotentialChoice(label, Modifier.fillMaxWidth(), q.show == i) { graph.findGear.edit(q.copy(show = i)) } }
    }
}

@Composable
internal fun PotentialHelpSheet(graph: AppGraph) {
    SheetBody("How the chance works", button = "Got it", onButton = { graph.nav.sheet = null }) {
        Text("Targets are substat minimums on one piece at +15. The main stat is a separate filter.", style = zen(13.sp, FontWeight.Medium))
        Text("The chance counts every way the remaining +3 steps can go: which substat each one hits and how much it adds. Two targets compete for the same steps.",
            style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Rare roll")
        Text("A value the game allows but almost never gives, like Speed +5 on level 85 gear. A piece that needs one gets no percent and waits in a fold at the end of the list.",
            style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Reforging")
        Text("Level 85 gear gains its reforge bonus when the finish includes reforging. Level 88 and 90 gear gains none.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Which pieces")
        Text("Level 85 and 88 Epic and Heroic pieces below +15, and finished pieces. No modification stones.", style = zen(12.sp, FontWeight.Medium))
        graph.findGear.result?.takeIf { it.unsupported > 0 }?.let { Text("${it.unsupported} older or unsupported pieces were left out of this search.", style = zen(12.sp, FontWeight.Medium, C.muted)) }
    }
}

@Composable
internal fun PotentialPieceSheet(graph: AppGraph, id: Long) {
    val result = graph.findGear.result ?: return
    val match = result.matches.firstOrNull { it.item.id == id } ?: return
    val q = result.query
    val item = match.item
    val view = if (item.enhance < 15) UpgradeRecord.fromScan(item) else item
    val o = match.outcome
    val reforgeOnly = reforgeOnly(match, q)
    SheetBody("Worth enhancing?", button = when { o.matchesNow -> null; item.enhance < 15 -> "Enhance over the game"; else -> "Track the reforge" }, onButton = {
        graph.nav.sheet = null
        graph.messages.show(graph.trackMatch(match, openOverlay = true))
    }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) { GearSearchIdentity(graph, item, q.targets.keys) }
        Card(shadow = null, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChanceText(match, 28)
                Column(Modifier.weight(1f)) {
                    Text(if (o.matchesNow) "Already there" else "reach " + q.targets.entries.joinToString(" · ") { (t, v) -> "${targetLabel(t)} ${targetValue(t, v)}" },
                        style = zen(13.sp))
                    Text(when {
                        o.matchesNow -> "Meets every target now"
                        o.rareOnly -> "only with a roll above the usual range"
                        reforgeOnly -> "after reforge"
                        q.reforge && match.reforgeAllowed && item.level == 85 -> "at +15, reforged"
                        else -> "at +15"
                    }, style = zen(11.sp, FontWeight.Medium, C.muted))
                }
            }
            VSpace(10.dp)
            GoalLines(view, q.goal().minimums, size = 13)
            if (item.enhance < 15) {
                VSpace(8.dp)
                Text("~${EsForecast.goldText(EsForecast.finishShare(item) * EsForecast.FULL_GOLD)} gold to +15", style = zen(12.sp, FontWeight.Medium, C.muted))
            }
        }
        HelpRow(graph)
    }
}

@Composable
private fun PotentialChoice(text: String, modifier: Modifier, selected: Boolean, leading: (@Composable () -> Unit)? = null, click: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.heightIn(min = 44.dp).clip(shape).background(if (selected) C.lightPink else C.card).border(2.dp, if (selected) C.edge else C.line, shape)
        .tap(click).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        leading?.invoke()
        FitText(text, zen(12.sp))
    }
}
