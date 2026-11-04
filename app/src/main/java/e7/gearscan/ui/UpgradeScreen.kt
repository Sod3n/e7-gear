package e7.gearscan.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.*
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.value.Chance

@Composable
fun UpgradeScreen(graph: AppGraph) {
    val box = graph.scan.box ?: return
    val t = graph.tracker
    val selected = t.record?.takeIf { it.scan == graph.scan.lastScan && it.item.id in box.itemsById }
    var choosing by rememberSaveable { mutableStateOf(selected == null) }
    val item = selected?.item
    val goal = t.goal != null || t.scoreGoal != null
    val out = t.readout.goalOutcome
    val overGame = when {
        choosing || item == null || t.sellQueueComplete -> null
        !t.hasTarget && !t.isSellQueue && t.scoreQuery == null && item.enhance == 15 -> null
        t.isSellQueue -> "Open over the game"
        // the button names the next step only when the forecast says it pays
        item.enhance < 15 && (!goal || out?.keepRolling == true) -> "Enhance over the game"
        item.enhance == 15 && goal && item.level == 85 && t.canReforge(item.id) && out?.let { it.chance > 0 && !it.matchesNow } == true -> "Reforge over the game"
        else -> "Open over the game"
    }
    Frame(null, null, primary = overGame?.let { Action(it) { graph.messages.show(t.openOverlay()) } }) {
        BackToPrevious()
        ScreenTitle("Track upgrades", "キロク")
        if (t.queue.isNotEmpty()) RowCard("Gear list", { graph.nav.go(listScreen(t)) }) {
            Text(if (t.sellQueueComplete) "All marked" else "${t.queueIndex + 1} of ${t.pendingQueue.size}", style = zen(12.sp))
        }
        if (t.needsRollRefresh) Card(shadow = null, padding = PaddingValues(12.dp)) {
            Text("This list has the old roll order", style = dela(15.sp))
            VSpace(8.dp)
            OutlineButton("Update Sell review", Modifier.fillMaxWidth(), height = 40.dp) { graph.nav.go(Screen.SellFilters) }
        }
        when {
            t.sellQueueComplete -> Card(shadow = null, padding = PaddingValues(14.dp)) {
                Text("List complete", style = dela(18.sp))
                if (t.canUndoStep) { VSpace(10.dp); OutlineButton("Undo the last mark", Modifier.fillMaxWidth(), height = 40.dp) { t.undoStep() } }
            }
            !choosing && selected != null && item != null -> Tracked(graph, t, selected) { choosing = true }
            else -> Choose(graph, t, box) { choosing = false }
        }
    }
}

private fun listScreen(t: TrackerFeature) = when {
    t.scoreQuery != null -> Screen.SellFilters
    t.queueSource == "heroes" -> Screen.HeroUpgrades()
    else -> Screen.GearPotential
}

@Composable
private fun Tracked(graph: AppGraph, t: TrackerFeature, selected: UpgradeRecord, change: () -> Unit) {
    val item = selected.item
    Card(shadow = null, padding = PaddingValues(12.dp)) {
        // a finished piece the goal counts reforged: its lines show the values after the reforge
        val reforged = t.goal?.takeIf { it.reforge && it.reforgeAllowed && t.readout.goalOutcome?.matchesNow != true }?.let { afterReforge(item) }
        GearSearchIdentity(graph, item, t.goal?.minimums?.keys ?: emptySet(),
            side = t.goal?.let { g -> { GoalLines(reforged ?: item, g.minimums, note = reforged?.let { "after reforge" }) } }) { Pill("Other piece", onClick = change) }
        t.notice?.let { VSpace(8.dp); Text(it, style = zen(11.sp, FontWeight.Medium, C.blue)) }
    }
    Card(shadow = null, padding = PaddingValues(14.dp)) {
        when {
            t.goal != null || t.scoreGoal != null -> GoalVerdict(graph, t, item)
            t.isRollReview -> Text(t.readout.title, style = dela(16.sp))
            else -> HeroAdvice(t, item)
        }
    }
    if (selected.history.size > 1) {
        SectionTitle("Your rolls")
        Card(shadow = null, padding = PaddingValues(12.dp)) { RollTable(selected.history.takeLast(4)) }
    }
    // what the rail over the game doesn't carry: fixing an entry, taking one back, closing it
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (UpgradeRecord.supported(item)) OutlineButton("Fix recorded stats", Modifier.fillMaxWidth(), height = 40.dp) {
            t.beginEdit(correction = true); graph.messages.show(t.openOverlay())
        }
        if (selected.history.size > 1) OutlineButton("Undo last entry", Modifier.fillMaxWidth(), height = 40.dp) {
            graph.messages.confirm("Undo the last entry?", "The +${item.enhance} values you recorded go away.", "Undo") { t.undo() }
        }
        if (t.shown) OutlineButton("Stop tracking", Modifier.fillMaxWidth(), height = 40.dp) { graph.overGame.stopTracking() }
    }
}

/** Tracking one piece ends a walk it is not part of: ask first while pieces are left in it. */
internal fun AppGraph.keepingWalk(id: Long, go: () -> Unit) {
    if (tracker.keepsWalk(id)) go()
    else messages.confirm("End your list?", "${tracker.pendingQueue.size} pieces are left in the list over the game.", "Track this one") { go() }
}

/** The one fact that decides: the chance to reach the goal, big, with what it is about under it. */
@Composable
private fun GoalVerdict(graph: AppGraph, t: TrackerFeature, item: Item) {
    val out = t.readout.goalOutcome
    val (big, color) = when {
        out == null || !out.supported -> "?" to C.muted
        out.rareOnly || !out.possible -> out.word() to C.deepPink
        else -> out.word() to C.blue
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FitText(big, dela(28.sp, color))
        Column(Modifier.weight(1f)) {
            t.scoreGoal?.let { g -> Text(g.label(), style = zen(13.sp)); Text(g.reference, style = zen(11.sp, FontWeight.Medium, C.muted)) }
            t.goal?.let { g ->
                Text(claims("reach " + g.minimums.entries.joinToString(" · ") { (s, v) -> "${targetLabel(s)} ${targetValue(s, fmtNum(v))}" }), style = zen(13.sp))
                Text(when {
                    out?.matchesNow == true -> "every target reached"
                    item.enhance == 15 && out?.chance == 1.0 -> "after reforge"
                    out?.rareOnly == true -> "only with a roll above the usual range"
                    out?.possible == false -> "out of reach now"
                    g.reforge && g.reforgeAllowed && item.level == 85 -> "at +15, reforged"
                    else -> "at +15"
                }, style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
    }
    VSpace(10.dp)
    OutlineButton(if (t.scoreQuery != null) "Change filters" else "Change targets", Modifier.fillMaxWidth(), height = 40.dp) {
        graph.nav.go(if (t.scoreQuery != null) Screen.SellFilters else Screen.GearPotential)
    }
}

/** Without a target: which saved heroes the piece could improve, one estimate per hero, and the finished piece's next steps. */
@Composable
private fun HeroAdvice(t: TrackerFeature, item: Item) {
    Text(t.readout.title, style = dela(16.sp))
    if (!t.checkRun.busy) t.checkRun.results.filter { it.current || it.hits.isNotEmpty() }.sortedByDescending { it.hits.size }.take(4).forEach { h ->
        VSpace(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(h.name, style = zen(13.sp), modifier = Modifier.weight(1f))
            if (h.current) Tag("UPGRADE NOW", bg = C.blue, size = 9)
            if (h.hits.isNotEmpty()) Text("~${h.hits.size * 100 / h.total}%", style = dela(15.sp, C.blue))
        }
        if (h.hits.isNotEmpty()) Text(if (h.hits.size == h.total) "An upgrade whatever the rolls" else h.minimal.firstOrNull()?.let { "From $it at +15" }.orEmpty(),
            style = zen(11.sp, FontWeight.Medium, C.muted))
    }
    if (item.enhance == 15) {
        val buttons = buildList<Pair<String, () -> Unit>> {
            if (item.level == 85 && t.canReforge(item.id)) add("Record the reforge" to { t.reforge() })
            if (t.worn.equippedOn == null) t.checkRun.equipHeroes.forEach { h -> add("Equipped on ${h.name}" to { t.worn.equip(h.id) }) }
            else add("Undo: equipped on ${t.worn.equippedOn}" to { t.worn.undoEquip() })
        }
        buttons.forEach { (label, act) -> VSpace(8.dp); OutlineButton(label, Modifier.fillMaxWidth(), height = 40.dp, onClick = act) }
    }
    if (!t.checkRun.busy) { VSpace(8.dp); OutlineButton("Recheck builds", Modifier.fillMaxWidth(), height = 40.dp) { t.checkRun.runCheck() } }
    t.checkRun.betterBet?.let { (id, _) -> VSpace(8.dp); OutlineButton("Track the better bet", Modifier.fillMaxWidth(), height = 40.dp) { t.switchTo(id) } }
}

/** Substats down the side as in the game, one column per recorded +3; a value that changed at a step is blue. */
@Composable
private fun RollTable(steps: List<Item>) {
    val types = steps.flatMap { p -> p.subs.map { it.type } }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(22.dp))
            steps.forEach { Text("+${it.enhance}", style = zen(11.sp, color = C.muted), textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        types.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(22.dp)) { StatIcon(type.stat, 13.dp, C.ink) }
                steps.forEachIndexed { i, p ->
                    val v = p.subs.firstOrNull { it.type == type }?.value
                    val before = steps.getOrNull(i - 1)?.subs?.firstOrNull { it.type == type }?.value
                    val changed = i > 0 && v != null && v != before
                    Text(v?.let(type::format) ?: "–", style = zen(12.sp, color = if (changed) C.blue else C.ink), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Choose(graph: AppGraph, t: TrackerFeature, box: e7.gearscan.data.Box, chosen: () -> Unit) {
    var set by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSet by rememberSaveable { mutableStateOf(false) }
    var slot by rememberSaveable { mutableStateOf<Slot?>(null) }
    var count by rememberSavedFor(set to slot, 20)
    val current = t.record?.item?.id
    RowCard("Set", { choosingSet = !choosingSet }) {
        set?.let { SetIcon(graph.content.content.set(it)?.icon, 18.dp) }
        Text(set?.let { graph.content.content.set(it)?.name } ?: "Any set", style = zen(12.sp))
    }
    if (choosingSet) CardGrid(listOf<String?>(null) + graph.content.content.sets.map { it.key }, columns = 4, gap = 6.dp) { s, m ->
        Pill(s?.let { graph.content.content.set(it)?.name } ?: "Any set", modifier = m, selected = set == s, onClick = { set = s; choosingSet = false })
    }
    CardGrid(listOf<Slot?>(null) + Slot.entries, columns = 3, gap = 6.dp) { s, m ->
        Pill(s?.label ?: "All", selected = slot == s, modifier = m, onClick = { slot = s })
    }
    val items = box.items.filter { it.id !in graph.marks.cleared && UpgradeRecord.supported(it) && (it.enhance < 15 || graph.records.records[it.id]?.scan == graph.scan.lastScan) && (slot == null || it.slot == slot) }
        .filter { set == null || it.set == set }
        .sortedWith(compareByDescending<Item> { graph.records.records[it.id]?.scan == graph.scan.lastScan }.thenBy { it.slot.ordinal }.thenByDescending { it.enhance })
    SectionTitle("Your gear", trailing = "${items.size}")
    items.take(count).forEach { original ->
        val item = graph.records.records[original.id]?.takeIf { it.scan == graph.scan.lastScan }?.item ?: UpgradeRecord.fromScan(original)
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { graph.keepingWalk(original.id) { t.selectPiece(original.id); chosen() } }) {
            GearSearchIdentity(graph, item) { if (item.id == current) Tag("TRACKING", bg = C.blue, size = 9) else ChevronSlot(true) }
        }
    }
    if (items.isEmpty()) Text("No piece to track in this slot and set.", style = zen(12.sp, FontWeight.Medium, C.muted))
    if (count < items.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
}
