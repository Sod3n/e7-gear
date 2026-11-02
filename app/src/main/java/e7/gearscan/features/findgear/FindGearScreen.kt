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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.*
import e7.gearscan.features.findgear.FindGearMatch

private fun targets(q: PotentialQuery) = q.targets.entries.joinToString(" · ") { (t, v) ->
    "${if (t.percentOfBase) t.stat.short else t.short} ${v.ifEmpty { "?" }}${if (t.showsPercent) "%" else ""}+"
}
private fun slots(q: PotentialQuery) = when (q.slots) {
    Slot.entries.toSet() -> "Any slot"
    Slot.entries.toSet() - Slot.BOOTS -> "No boots"
    else -> if (q.slots.size <= 2) q.slots.joinToString { it.label } else "${q.slots.size} slots"
}
private fun sets(model: AppModel, q: PotentialQuery) = when {
    q.sets.isEmpty() -> "Any set"
    q.sets.size <= 2 -> q.sets.joinToString(" / ") { model.content.set(it)?.name ?: it.removeSuffix("Set") }
    else -> "${q.sets.size} sets"
}
internal val AppModel.f get() = graph.findGear
internal fun AppModel.show(o: Outcome) = graph.messages.show(o)
internal fun open(model: AppModel, kind: SheetKind, id: Long = 0) { model.sheet = Sheet(kind, id) }

@Composable
fun FindGearScreen(model: AppModel) {
    val q = model.f.query
    val result = model.f.result
    var tab by rememberSaveable { mutableStateOf(if (result == null) 0 else 1) }
    var count by rememberSavedFor(result, 20)
    val resultId = System.identityHashCode(result)
    var seenResult by rememberSaveable { mutableStateOf(resultId) }
    LaunchedEffect(result) { if (result != null && seenResult != resultId) tab = 1; seenResult = resultId }
    key(tab, result) {
        Frame(null, null, actions = if (tab == 0) ({
            PinkButton(if (model.f.busy) "Searching" else "Find gear", Modifier.weight(1f), enabled = !model.f.busy) { model.show(model.f.find()) }
        }) else if (result != null && result.matches.isNotEmpty()) ({
            val fresh = !model.f.stale
            PinkButton(if (fresh) "Track all ${result.matches.size} matches" else "Update matches", Modifier.weight(1f), enabled = !model.f.busy) {
                model.show(if (fresh) model.f.trackAll() else model.f.find())
            }
        }) else null) {
            BackToPrevious()
            ScreenTitle("Find gear")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkText("Help") { open(model, SheetKind.POTENTIAL_HELP) }
            }
            Segmented(listOf("Set up", "Matches"), tab) { tab = it }
            if (tab == 0) {
                Card(shadow = C.pink, padding = PaddingValues(12.dp)) {
                    ListRow({ open(model, SheetKind.POTENTIAL_TARGETS) }, leading = { Tag("GOAL") }) {
                        Text(targets(q).ifEmpty { "Choose substats" }, style = dela(16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("On one piece at +15", style = zen(11.sp, FontWeight.Medium, C.muted))
                    }
                }
                SectionTitle("Your filters")
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    RowCard("Slots", { open(model, SheetKind.POTENTIAL_SLOTS) }) {
                        if (q.slots.size <= 2) q.slots.forEach { GearIcon(it.icon, 18.dp, C.ink) }
                        Text(slots(q), style = zen(12.sp))
                    }
                    RowCard("Sets", { open(model, SheetKind.POTENTIAL_SETS) }) {
                        q.sets.take(3).forEach { SetIcon(model.content.set(it)?.icon, 18.dp) }
                        Text(sets(model, q), style = zen(12.sp))
                    }
                    RowCard("Mains", { open(model, SheetKind.POTENTIAL_MAINS) }) {
                        Text(if (q.mains.isEmpty()) "Any main stat" else q.mains.joinToString { it.short }, style = zen(12.sp))
                    }
                    RowCard("Finish", { open(model, SheetKind.POTENTIAL_OPTIONS) }) {
                        Text(if (q.reforge) "+15 & reforged" else "+15, before reforging", style = zen(12.sp))
                    }
                    RowCard("Show", { open(model, SheetKind.POTENTIAL_OPTIONS) }) {
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
                    PinkButton("Set up search", Modifier.fillMaxWidth()) { tab = 0 }
                }
            } else {
                val applied = result.query
                Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(targets(applied), style = zen(13.sp, color = C.blue))
                            Text("${slots(applied)} · ${sets(model, applied)}", style = zen(11.sp, FontWeight.Medium, C.muted))
                        }
                        Pill("Edit", onClick = { tab = 0 })
                    }
                }
                if (model.f.stale) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.lightPink).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Search changed", style = zen(12.sp), modifier = Modifier.weight(1f))
                        LinkText("Update") { model.show(model.f.find()) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${result.matches.size} pieces", style = dela(16.sp), modifier = Modifier.weight(1f))
                    Text("Best odds first", style = zen(11.sp, color = C.muted))
                }
                if (model.f.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        Column(Modifier.tap { open(model, SheetKind.POTENTIAL_PIECE, match.item.id) }) {
                            GearSearchIdentity(model, match.item, applied.targets.keys) {
                                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                                    FitText(if (match.outcome.rareOnly) "Rare" else match.outcome.label(), dela(15.sp, if (match.outcome.rareOnly) C.deepPink else C.blue))
                                    Text(if (match.outcome.rareOnly) "rolls needed" else "chance", style = zen(9.sp, FontWeight.Medium, C.muted))
                                }
                            }
                            VSpace(8.dp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(gearOwner(model, match.item), style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
                                Text(finishAction(match, applied), style = zen(10.sp, color = C.blue))
                                Spacer(Modifier.width(7.dp)); ChevronSlot(true)
                            }
                        }
                    }
                }
                if (count < result.matches.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
                LinkText("About this search") { open(model, SheetKind.POTENTIAL_HELP) }
            }
        }
    }
}

internal fun gearOwner(model: AppModel, item: Item) = item.equippedOn?.let { model.hero(it)?.let(model::heroName) ?: "Hero not in scan" } ?: "In bag"
internal fun finishAction(match: FindGearMatch, q: PotentialQuery) = when {
    match.outcome.matchesNow -> "Ready now"
    match.item.enhance == 15 -> "Reforge only"
    q.reforge && match.reforgeAllowed && match.item.level == 85 -> "Finish + reforge"
    else -> "Finish to +15"
}

/** The app's gear tile and 2-column stat grid, with no nested item card. */
@Composable
internal fun GearSearchIdentity(model: AppModel, original: Item, highlight: Set<GearStat> = emptySet(), explicitGameScore:Boolean=false,
                                owner: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val item = if (original.enhance < 15) UpgradeRecord.fromScan(original) else original
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 4.dp)) { GearTile(item, 46.dp, setIcon = model.content.set(item.set)?.icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${model.content.set(item.set)?.name ?: item.set.removeSuffix("Set")} ${item.slot.label}", style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                GearPositionLabel(model, item,explicitGameScore)
                if (owner) item.equippedOn?.let { model.hero(it) }?.let { h -> OwnerTag(model.heroName(h)) }
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
internal fun GearPositionLabel(model: AppModel, item: Item, explicitGameScore:Boolean=false) {
    if (!item.ghost) Text("ES ${e7.gearscan.data.InventoryOrder.score(item)}", style = zen(10.sp, FontWeight.Medium, C.blue))
}
