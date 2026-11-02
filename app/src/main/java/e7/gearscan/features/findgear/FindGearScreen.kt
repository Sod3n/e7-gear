package e7.gearscan.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.*
import e7.gearscan.value.Count
import e7.gearscan.features.findgear.FindGearMatch
import e7.gearscan.state.then
import e7.gearscan.ui.Outcome

fun AppGraph.trackMatch(match: FindGearMatch, openOverlay: Boolean = false): Outcome =
    findGear.toTrack(match).then { (item, goal) -> tracker.starts.track(item, goal, openOverlay) }

fun AppGraph.trackAllMatches(openOverlay: Boolean = true): Outcome = findGear.toTrackAll().then { tracker.starts.trackQueue(it, openOverlay) }

private fun targetPieces(q: PotentialQuery) = q.targets.entries.map { (t, v) ->
    "${if (t.percentOfBase) t.stat.short else t.short} ${v.ifEmpty { "?" }}${if (t.showsPercent) "%" else ""}+"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetsLine(q: PotentialQuery, style: TextStyle, empty: String = "") {
    val pieces = targetPieces(q)
    if (pieces.isEmpty()) { Text(empty, style = style); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        pieces.forEachIndexed { i, piece -> Text(if (i < pieces.lastIndex) "$piece ·" else piece, style = style, softWrap = false) }
    }
}
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
internal fun open(graph: AppGraph, kind: SheetKind, id: Long = 0) { graph.nav.sheet = Sheet(kind, id) }

@Composable
@OptIn(LinkStyle::class)
fun FindGearScreen(graph: AppGraph) {
    val q = graph.findGear.query
    val result = graph.findGear.result
    var tab by rememberSaveable { mutableStateOf(if (result == null) 0 else 1) }
    var count by rememberSavedFor(result, 20)
    val resultId = System.identityHashCode(result)
    var seenResult by rememberSaveable { mutableStateOf(resultId) }
    LaunchedEffect(result) { if (result != null && seenResult != resultId) tab = 1; seenResult = resultId }
    key(tab, result) {
        val fresh = !graph.findGear.stale
        Frame(null, null, primary = if (tab == 0) {
            Action(if (graph.findGear.busy) "Searching" else "Find gear", enabled = !graph.findGear.busy) { graph.messages.show(graph.findGear.find()) }
        } else if (result != null && result.matches.isNotEmpty()) {
            Action(if (fresh) Count(result.matches.size).of("match", "matches").let { "Track all $it" } else "Update matches", enabled = !graph.findGear.busy) {
                graph.messages.show(if (fresh) graph.trackAllMatches() else graph.findGear.find())
            }
        } else null) {
            BackToPrevious()
            ScreenTitle("Find gear")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkText("Help") { open(graph, SheetKind.POTENTIAL_HELP) }
            }
            Segmented(listOf("Set up", "Matches"), tab) { tab = it }
            if (tab == 0) {
                Card(shadow = C.pink, padding = PaddingValues(12.dp)) {
                    ListRow({ open(graph, SheetKind.POTENTIAL_TARGETS) }, leading = { Tag("GOAL") }) {
                        TargetsLine(q, dela(16.sp), "Choose substats")
                        Text("On one piece at +15", style = zen(11.sp, FontWeight.Medium, C.muted))
                    }
                }
                SectionTitle("Your filters")
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    RowCard("Slots", { open(graph, SheetKind.POTENTIAL_SLOTS) }) {
                        if (q.slots.size <= 2) q.slots.forEach { GearIcon(it.icon, 18.dp, C.ink) }
                        Text(slots(q), style = zen(12.sp))
                    }
                    RowCard("Sets", { open(graph, SheetKind.POTENTIAL_SETS) }) {
                        q.sets.take(3).forEach { SetIcon(graph.content.content.set(it)?.icon, 18.dp) }
                        Text(sets(graph, q), style = zen(12.sp))
                    }
                    RowCard("Mains", { open(graph, SheetKind.POTENTIAL_MAINS) }) {
                        Text(if (q.mains.isEmpty()) "Any main stat" else q.mains.joinToString { it.short }, style = zen(12.sp))
                    }
                    RowCard("Finish", { open(graph, SheetKind.POTENTIAL_OPTIONS) }) {
                        Text(if (q.reforge) "+15 & reforged" else "+15, before reforging", style = zen(12.sp))
                    }
                    RowCard("Show", { open(graph, SheetKind.POTENTIAL_OPTIONS) }) {
                        Text(listOf("Upgrades", "Unfinished", "All gear")[q.show] +
                            if (q.minimumChance.toDoubleOrNull()?.let { it > 0 } == true) " · ${q.minimumChance}%+ chance" else " · any chance", style = zen(12.sp))
                    }
                }
                Text("Choose a target. Find the pieces worth a try.", style = zen(12.sp, FontWeight.Medium, C.muted))
            } else if (result == null) {
                Card {
                    Text("Your next upgrade starts here", style = dela(17.sp))
                    VSpace(6.dp)
                    Text("Set the stats you want, then search your gear.", style = zen(12.sp, FontWeight.Medium, C.muted))
                    VSpace(12.dp)
                    CardAction(Action("Set up search") { tab = 0 })
                }
            } else {
                val applied = result.query
                Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            TargetsLine(applied, zen(13.sp, color = C.blue))
                            if (graph.findGear.stale) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Search changed", style = zen(11.sp, FontWeight.Medium, C.deepPink))
                                LinkText("Update", size = 11) { graph.messages.show(graph.findGear.find()) }
                            } else Text("${slots(applied)} · ${sets(graph, applied)}", style = zen(11.sp, FontWeight.Medium, C.muted))
                        }
                        Pill("Edit", onClick = { tab = 0 })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountText(Count(result.matches.size), "piece", style = dela(16.sp), modifier = Modifier.weight(1f))
                    Text("Best odds first", style = zen(11.sp, color = C.muted))
                }
                if (graph.findGear.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(); Text("Checking your gear", style = zen(12.sp))
                }
                if (result.matches.isEmpty()) Card {
                    Text("Nothing fits these targets", style = dela(16.sp))
                    VSpace(6.dp)
                    Text("Try another set or a lower minimum.", style = zen(12.sp, FontWeight.Medium, C.muted))
                    VSpace(10.dp)
                    OutlineButton("Adjust search", Modifier.fillMaxWidth(), height = 42.dp) { tab = 0 }
                }
                result.matches.take(count).forEach { match ->
                    Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp)) {
                        Column(Modifier.tap { open(graph, SheetKind.POTENTIAL_PIECE, match.item.id) }) {
                            GearSearchIdentity(graph, match.item, applied.targets.keys) {
                                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                                    FitText(if (match.outcome.rareOnly) "Rare" else match.outcome.label(), dela(15.sp, if (match.outcome.rareOnly) C.deepPink else C.blue))
                                    Text(if (match.outcome.rareOnly) "rolls needed" else "chance", style = zen(9.sp, FontWeight.Medium, C.muted))
                                }
                            }
                            VSpace(8.dp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(gearOwner(graph, match.item), style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
                                Text(finishAction(match, applied), style = zen(10.sp, color = C.blue))
                                Spacer(Modifier.width(7.dp)); ChevronSlot(true)
                            }
                        }
                    }
                }
                if (count < result.matches.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
                LinkText("About this search") { open(graph, SheetKind.POTENTIAL_HELP) }
            }
        }
    }
}

internal fun gearOwner(graph: AppGraph, item: Item) = item.equippedOn?.let { graph.heroes.hero(it)?.let(graph.heroes::heroName) ?: "Hero not in scan" } ?: "In bag"
internal fun finishAction(match: FindGearMatch, q: PotentialQuery) = when {
    match.outcome.matchesNow -> "Ready now"
    match.item.enhance == 15 -> "Reforge only"
    q.reforge && match.reforgeAllowed && match.item.level == 85 -> "Finish + reforge"
    else -> "Finish to +15"
}

/** The app's gear tile and 2-column stat grid, with no nested item card. */
@Composable
internal fun GearSearchIdentity(graph: AppGraph, original: Item, highlight: Set<GearStat> = emptySet(), explicitGameScore:Boolean=false,
                                owner: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val item = if (original.enhance < 15) UpgradeRecord.fromScan(original) else original
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 4.dp)) { GearTile(item, 46.dp, setIcon = graph.content.content.set(item.set)?.icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${graph.content.content.set(item.set)?.name ?: item.set.removeSuffix("Set")} ${item.slot.label}", style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatValue(item.mainType.stat, item.mainType.format(item.mainValue), C.ink, 12)
                        Text("Lv.${item.level}", style = zen(10.sp, FontWeight.Medium, C.muted))
                    }
                }
                trailing?.invoke()
            }
            VSpace(2.dp)
            GearSearchSubs(item, highlight)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearPositionLabel(graph, item,explicitGameScore)
                if (owner) item.equippedOn?.let { graph.heroes.hero(it) }?.let { h -> OwnerTag(graph.heroes.heroName(h)) }
            }
        }
    }
}

/** Which hero wears the piece now, as a small outlined tag. */
@Composable
internal fun OwnerTag(name: String) {
    Text("on $name", style = zen(10.sp, FontWeight.Bold, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.border(1.dp, C.muted, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
}

@Composable
internal fun GearSearchSubs(item: Item, highlight: Set<GearStat> = emptySet()) {
    CardGrid(item.subs, columns = 2, gap = 2.dp) { s, m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = if (s.type in highlight) C.blue else C.muted
            StatIcon(s.type.stat, 12.dp, color)
            Text(s.type.format(s.value), style = zen(11.sp, color = color))
        }
    }
}

@Composable
internal fun GearPositionLabel(graph: AppGraph, item: Item, explicitGameScore:Boolean=false) {
    if (!item.ghost) Text("ES ${e7.gearscan.data.InventoryOrder.score(item)}", style = zen(10.sp, FontWeight.Medium, C.blue))
}
