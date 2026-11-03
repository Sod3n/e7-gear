package e7.gearscan.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.*
import e7.gearscan.value.Chance
import e7.gearscan.value.Count
import e7.gearscan.features.findgear.FindGearMatch
import e7.gearscan.features.findgear.FindGearResult
import e7.gearscan.state.then
import e7.gearscan.ui.Outcome

fun AppGraph.trackMatch(match: FindGearMatch, openOverlay: Boolean = false): Outcome =
    findGear.toTrack(match).then { (item, goal) -> tracker.starts.track(item, goal, openOverlay) }

fun AppGraph.trackAllMatches(openOverlay: Boolean = true): Outcome = findGear.toTrackAll().then { tracker.starts.trackQueue(it, openOverlay) }

internal fun targetLabel(t: GearStat) = if (t.percentOfBase) t.stat.label + " %" else t.stat.label
internal fun targetValue(t: GearStat, v: String) = "${v.ifEmpty { "?" }}${if (t.showsPercent) "%" else ""}+"

/** Minimum chance stops, in percent; the first means "any chance, and pieces that need a rare roll too". */
internal val CHANCE_STOPS = listOf(-1.0, 0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 50.0, 75.0)

internal fun chanceStop(q: PotentialQuery): Int {
    val m = q.minimumChance.toDoubleOrNull() ?: 0.0
    if (m <= 0) return if (q.rareOnly) 0 else 1
    return (2 until CHANCE_STOPS.size).minBy { kotlin.math.abs(kotlin.math.ln(CHANCE_STOPS[it] / m)) }
}

internal fun chanceStopLabel(i: Int) = when (i) { 0 -> "Any, rare rolls too"; 1 -> "Any"; else -> "${fmtNum(CHANCE_STOPS[i])}%+" }

internal fun withChanceStop(q: PotentialQuery, i: Int) = when (i) {
    0 -> q.copy(minimumChance = "0", rareOnly = true)
    1 -> q.copy(minimumChance = "0", rareOnly = false)
    else -> q.copy(minimumChance = fmtNum(CHANCE_STOPS[i]), rareOnly = false)
}

internal val PIECES = listOf("Not there yet", "Below +15", "All gear")

private fun slots(q: PotentialQuery) = when (q.slots) {
    Slot.entries.toSet() -> "Any slot"
    Slot.entries.toSet() - Slot.BOOTS -> "No boots"
    else -> if (q.slots.size <= 2) q.slots.joinToString { it.label } else "${q.slots.size} slots"
}
private fun sets(graph: AppGraph, q: PotentialQuery) = when {
    q.sets.isEmpty() -> "Any set"
    q.sets.size <= 2 -> q.sets.joinToString(" / ") { graph.content.content.set(it)?.name ?: it.removeSuffix("Set") }
    else -> "${q.sets.size} sets"
}
private fun mains(q: PotentialQuery) = if (q.mains.isEmpty()) "Any main" else q.mains.joinToString { it.short } + " main"

/** Filters left at something other than the default, so a summary shows everything that narrows the list. */
private fun changedFilters(q: PotentialQuery) = listOfNotNull(
    chanceStop(q).takeIf { it >= 2 }?.let { "Chance " + chanceStopLabel(it) },
    PIECES[q.show].takeIf { q.show != 0 },
    "No reforge".takeIf { !q.reforge },
)

internal fun open(graph: AppGraph, kind: SheetKind, id: Long = 0) { graph.nav.sheet = Sheet(kind, id) }

/** The targets as the game shows stats: icon, then the minimum. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalTargets(q: PotentialQuery, style: TextStyle, full: Boolean, extra: List<String> = emptyList()) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        q.targets.forEach { (t, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatIcon(t.stat, (style.fontSize.value).dp, style.color)
                Text(if (full) "${targetLabel(t)} ${targetValue(t, v)}" else targetValue(t, v), style = style, softWrap = false)
            }
        }
        extra.forEach { Text(it, style = style, softWrap = false) }
    }
}

@Composable
@OptIn(LinkStyle::class)
fun FindGearScreen(graph: AppGraph) {
    val q = graph.findGear.query
    val result = graph.findGear.result
    var tab by rememberSaveable { mutableStateOf(if (result == null) 0 else 1) }
    val resultId = System.identityHashCode(result)
    var seenResult by rememberSaveable { mutableStateOf(resultId) }
    LaunchedEffect(result) { if (result != null && seenResult != resultId) tab = 1; seenResult = resultId }
    key(tab, result) {
        val fresh = !graph.findGear.stale
        val odds = result?.open(graph.marks.cleared)?.withOdds.orEmpty()
        Frame(null, null, primary = when {
            tab == 0 -> Action(if (graph.findGear.busy) "Searching" else "Find gear", enabled = !graph.findGear.busy) { graph.messages.show(graph.findGear.find()) }
            result == null -> null
            !fresh -> Action("Update matches", enabled = !graph.findGear.busy) { graph.messages.show(graph.findGear.find()) }
            odds.isNotEmpty() -> Action("Track all ${Count(odds.size).n}", enabled = !graph.findGear.busy) { graph.messages.show(graph.trackAllMatches()) }
            else -> null
        }) {
            BackToPrevious()
            ScreenTitle("Find gear", "サガス")
            Segmented(listOf("Set up", "Matches"), tab) { tab = it }
            if (tab == 0) SetUp(graph, q)
            else if (result == null) {
                Card {
                    Text("Your next upgrade starts here", style = dela(17.sp))
                    VSpace(6.dp)
                    Text("Set the stats you want, then search your gear.", style = zen(12.sp, FontWeight.Medium, C.muted))
                    VSpace(12.dp)
                    CardAction(Action("Set up search") { tab = 0 })
                }
            } else Matches(graph, result, fresh) { tab = 0 }
        }
    }
}

@Composable
private fun SetUp(graph: AppGraph, q: PotentialQuery) {
    Card(shadow = C.pink, padding = PaddingValues(12.dp)) {
        ListRow({ open(graph, SheetKind.POTENTIAL_TARGETS) }, leading = { Tag("GOAL") }) {
            if (q.targets.isEmpty()) Text("Choose substats", style = dela(16.sp))
            else GoalTargets(q, dela(16.sp), full = true)
            Text("On one piece at +15", style = zen(11.sp, FontWeight.Medium, C.muted))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your filters", Modifier.weight(1f))
        Pill("Reset", onClick = { graph.messages.askReset("Reset the search?", "Targets and filters go back to Speed 20, no boots.", graph.findGear::resetSearch) })
    }
    val label = with(LocalDensity.current) { 64.sp.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        RowCard("Slots", { open(graph, SheetKind.POTENTIAL_SLOTS) }, labelWidth = label) {
            if (q.slots.size <= 2) q.slots.forEach { GearIcon(it.icon, 18.dp, C.ink) }
            Text(slots(q), style = zen(12.sp))
        }
        RowCard("Sets", { open(graph, SheetKind.POTENTIAL_SETS) }, labelWidth = label) {
            q.sets.take(3).forEach { SetIcon(graph.content.content.set(it)?.icon, 18.dp) }
            Text(sets(graph, q), style = zen(12.sp))
        }
        RowCard("Main stat", { open(graph, SheetKind.POTENTIAL_MAINS) }, labelWidth = label) {
            q.mains.take(3).forEach { StatIcon(it.stat, 16.dp) }
            Text(if (q.mains.isEmpty()) "Any" else if (q.mains.size <= 3) q.mains.joinToString { it.short } else "${q.mains.size} stats", style = zen(12.sp))
        }
        RowCard("Finish", { open(graph, SheetKind.POTENTIAL_FINISH) }, labelWidth = label) {
            Text(if (q.reforge) "+15, reforged" else "+15", style = zen(12.sp))
        }
        RowCard("Chance", { open(graph, SheetKind.POTENTIAL_CHANCE) }, labelWidth = label) {
            Text(chanceStopLabel(chanceStop(q)), style = zen(12.sp))
        }
        RowCard("Pieces", { open(graph, SheetKind.POTENTIAL_PIECES) }, labelWidth = label) {
            Text(PIECES[q.show], style = zen(12.sp))
        }
    }
    HelpRow(graph)
}

@Composable
internal fun HelpRow(graph: AppGraph) {
    Card(shadowOffset = 3.dp, radius = 14.dp, border = 2.dp, padding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)) {
        ListRow({ open(graph, SheetKind.POTENTIAL_HELP) }) { Text("How the chance works", style = zen(13.sp)) }
    }
}

@Composable
private fun Matches(graph: AppGraph, result: FindGearResult, fresh: Boolean, edit: () -> Unit) {
    val applied = result.query
    val grouped = graph.findGear.grouped
    val open = result.open(graph.marks.cleared)
    val odds = remember(result, grouped, graph.scan.box, graph.marks.cleared) { graph.findGear.ordered(open.withOdds) { it.item } }
    val rare = remember(result, grouped, graph.scan.box, graph.marks.cleared) { graph.findGear.ordered(open.rareOnly) { it.item } }
    var count by rememberSavedFor(result, 20)
    var rareOpen by rememberSaveable(System.identityHashCode(result)) { mutableStateOf(false) }
    var rareCount by rememberSavedFor(result to "rare", 20)
    Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                GoalTargets(applied, zen(13.sp, color = C.blue), full = false, extra = changedFilters(applied))
                if (!fresh) Text(if (graph.findGear.query == applied) "Gear changed since this search" else "Filters changed", style = zen(11.sp, FontWeight.Medium, C.deepPink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text("${slots(applied)} · ${sets(graph, applied)}${if (applied.mains.isNotEmpty()) " · " + mains(applied) else ""}", style = zen(11.sp, FontWeight.Medium, C.muted),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Pill("Edit", onClick = edit)
        }
    }
    if (graph.findGear.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spinner(); Text("Checking your gear", style = zen(12.sp))
    }
    val dim = if (fresh) Modifier else Modifier.alpha(.45f)
    if (odds.isNotEmpty()) Text("${odds.size} can reach it at +15", style = dela(16.sp))
    else if (rare.isNotEmpty()) Text("None with usual rolls", style = dela(16.sp))
    else Card {
        val best = result.bestBelow
        if (best != null) {
            Text("Nothing at ${chanceStopLabel(chanceStop(applied)).removeSuffix("+")} or more", style = dela(16.sp))
            VSpace(6.dp)
            Text("Best below that: ${Chance(best.outcome.chance).text}, ${pieceName(graph, best.item)} +${best.item.enhance}", style = zen(12.sp, FontWeight.Medium, C.muted))
            VSpace(12.dp)
            val stop = (CHANCE_STOPS.size - 1 downTo 1).first { it == 1 || CHANCE_STOPS[it] <= best.outcome.chance * 100 }
            CardAction(Action(if (stop == 1) "Show any chance" else "Show ${chanceStopLabel(stop).removeSuffix("+")} and up") {
                graph.findGear.edit(withChanceStop(graph.findGear.query, stop)); graph.messages.show(graph.findGear.find())
            })
        } else {
            Text("No piece can reach these targets", style = dela(16.sp))
            VSpace(6.dp)
            Text("Lower a target or widen the filters.", style = zen(12.sp, FontWeight.Medium, C.muted))
            if (result.unsupported > 0) Text("${result.unsupported} not checked: no forecast for them.", style = zen(12.sp, FontWeight.Medium, C.muted))
            VSpace(12.dp)
            if (result.rareHidden > 0) CardAction(Action("Show ${result.rareHidden} with rare rolls") {
                graph.findGear.edit(withChanceStop(graph.findGear.query, 0)); graph.messages.show(graph.findGear.find())
            })
            else OutlineButton("Edit search", Modifier.fillMaxWidth(), height = 42.dp, onClick = edit)
        }
    }
    if (odds.isNotEmpty() || rare.isNotEmpty()) Segmented(listOf("Best chance", "By set"), if (grouped) 1 else 0) { graph.findGear.grouped = it == 1 }
    Column(dim, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupedRows(graph, grouped, odds.take(count), odds, { it.item }) { MatchCard(graph, it, applied) }
        if (count < odds.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
        if (rare.isNotEmpty()) Fold("${rare.size} need a rare roll", rareOpen, { rareOpen = !rareOpen }) {
            Text("A rare roll is a value the game allows but almost never gives, like Speed +5 on level 85 gear. It lands too seldom to put a " +
                "number on, so these pieces show no percent and stay out of the count and Track all.", style = zen(12.sp, FontWeight.Medium, C.muted))
            GroupedRows(graph, grouped, rare.take(rareCount), rare, { it.item }) { MatchCard(graph, it, applied) }
            if (rareCount < rare.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { rareCount += 20 }
        }
    }
}

internal fun reforgeOnly(match: FindGearMatch, q: PotentialQuery) =
    !match.outcome.matchesNow && match.item.enhance == 15 && q.reforge && match.reforgeAllowed && match.item.level == 85

@Composable
private fun MatchCard(graph: AppGraph, match: FindGearMatch, q: PotentialQuery) {
    val view = if (match.item.enhance < 15) UpgradeRecord.fromScan(match.item) else match.item
    Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { open(graph, SheetKind.POTENTIAL_PIECE, match.item.id) }) {
        GearSearchIdentity(graph, match.item, q.targets.keys,
            side = { GoalLines(view, q.goal().minimums, note = if (reforgeOnly(match, q)) "after reforge" else null) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChanceText(match, 15)
                ChevronSlot(true)
            }
        }
    }
}

@Composable
internal fun ChanceText(match: FindGearMatch, size: Int) {
    val out = match.outcome
    FitText(out.word(), dela(size.sp, if (out.rareOnly || !out.possible) C.deepPink else C.blue))
}
