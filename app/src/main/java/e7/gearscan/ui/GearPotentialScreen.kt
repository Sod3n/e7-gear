package e7.gearscan.ui

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
private fun open(model: AppModel, kind: SheetKind, id: Long = 0) { model.sheet = Sheet(kind, id) }

@Composable
fun GearPotentialScreen(model: AppModel) {
    val q = model.potentialQuery
    val result = model.potentialResult
    var tab by remember { mutableStateOf(if (result == null) 0 else 1) }
    var count by remember(result) { mutableStateOf(20) }
    LaunchedEffect(result) { if (result != null) tab = 1 }
    key(tab, result) {
        Frame(null, null, actions = if (tab == 0) ({
            PinkButton(if (model.potentialBusy) "Searching" else "Find gear", Modifier.weight(1f), enabled = !model.potentialBusy) { model.findGearPotential() }
        }) else if (result != null && result.matches.isNotEmpty()) ({
            val fresh = q == result.query && result.scan == model.lastScan && result.observations == model.upgrades.records.hashCode()
            PinkButton(if (fresh) "Track all ${result.matches.size} matches" else "Update matches", Modifier.weight(1f), enabled = !model.potentialBusy) {
                if (fresh) model.trackAllPotential() else model.findGearPotential()
            }
        }) else null) {
            BackToPrevious(model)
            ScreenTitle("Gear potential")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkText("Inventory order") { open(model, SheetKind.INVENTORY_ORDER) }
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
                model.potentialError?.let { Text(it, style = zen(12.sp, color = C.deepPink)) }
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
                if (q != applied || result.scan != model.lastScan || result.observations != model.upgrades.records.hashCode()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.lightPink).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Search changed", style = zen(12.sp), modifier = Modifier.weight(1f))
                        LinkText("Update") { model.findGearPotential() }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${result.matches.size} pieces", style = dela(16.sp), modifier = Modifier.weight(1f))
                    Text("Best odds first", style = zen(11.sp, color = C.muted))
                }
                if (model.potentialBusy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
internal fun finishAction(match: AppModel.PotentialMatch, q: PotentialQuery) = when {
    match.outcome.matchesNow -> "Ready now"
    match.item.enhance == 15 -> "Reforge only"
    q.reforge && match.reforgeAllowed && match.item.level == 85 -> "Finish + reforge"
    else -> "Finish to +15"
}

/** The app's gear tile and 2-column stat grid, with no nested item card. */
@Composable
internal fun GearSearchIdentity(model: AppModel, original: Item, highlight: Set<GearStat> = emptySet(), explicitGameScore:Boolean=false, trailing: (@Composable () -> Unit)? = null) {
    val item = if (original.enhance < 15) UpgradeRecord.fromScan(original) else original
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 4.dp)) { GearTile(item, 46.dp, setIcon = model.content.set(item.set)?.icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${model.content.set(item.set)?.name ?: item.set.removeSuffix("Set")} ${item.slot.label}", style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${item.mainType.short} ${item.mainType.format(item.mainValue)} · Lv.${item.level}", style = zen(10.sp, FontWeight.Medium, C.muted))
                }
                trailing?.invoke()
            }
            VSpace(2.dp)
            GearSearchSubs(item, highlight)
            GearPositionLabel(model, item,explicitGameScore)
        }
    }
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
internal fun PotentialTargetsSheet(model: AppModel) {
    val q = model.potentialQuery
    var adding by remember { mutableStateOf(false) }
    SheetBody("Substat targets", button = "Done", onButton = { model.sheet = null }) {
        Text("All targets on one piece.", style = zen(12.sp, FontWeight.Medium, C.muted))
        q.targets.forEach { (t, value) ->
            val limits = e7.gearscan.opt.GearBounds.target(t, q.reforge, q.show == 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatIcon(t.stat, 18.dp)
                Text(t.short, style = zen(13.sp), modifier = Modifier.width(52.dp))
                PotentialNumber(value, "Minimum ${t.short}", Modifier.weight(1f), limits.minimum, limits.maximum) { model.updatePotentialQuery(q.copy(targets = q.targets + (t to it))) }
                Text(if (t.showsPercent) "%+" else "+", style = zen(12.sp, color = C.muted))
                Box(Modifier.size(38.dp).tap { model.updatePotentialQuery(q.copy(targets = q.targets - t)) }, contentAlignment = Alignment.Center) { Text("×", style = dela(16.sp)) }
            }
        }
        if (q.targets.size < 4) OutlineButton("Add substat", Modifier.fillMaxWidth(), height = 42.dp) { adding = !adding }
        if (adding) CardGrid(GearStat.entries.filter { it !in q.targets }, columns = 3, gap = 7.dp) { t, m ->
            PotentialChoice(t.short, m, false) { model.updatePotentialQuery(q.copy(targets = q.targets + (t to ""))); adding = false }
        }
    }
}

@Composable
internal fun PotentialSlotsSheet(model: AppModel) {
    val q = model.potentialQuery
    SheetBody("Equipment slots", trailing = { LinkText("All") { model.updatePotentialQuery(q.copy(slots = Slot.entries.toSet())) } }, button = "Done", onButton = { model.sheet = null }) {
        CardGrid(Slot.entries, columns = 3) { s, m ->
            val selected = s in q.slots
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (selected) C.lightPink else C.card).border(2.dp, if (selected) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { model.updatePotentialQuery(q.copy(slots = if (selected) q.slots - s else q.slots + s)) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GearIcon(s.icon, 30.dp, C.ink); VSpace(8.dp); FitText(s.label, zen(12.sp))
            }
        }
        LinkText("Everything except boots") { model.updatePotentialQuery(q.copy(slots = Slot.entries.toSet() - Slot.BOOTS)) }
    }
}

@Composable
internal fun PotentialSetsSheet(model: AppModel) {
    val q = model.potentialQuery
    var query by remember { mutableStateOf("") }
    SheetBody("Sets", trailing = { LinkText("Any set") { model.updatePotentialQuery(q.copy(sets = emptySet())) } }, button = "Done", onButton = { model.sheet = null }) {
        NameFilter(query, "Name") { query = it }
        Text(if (q.sets.isEmpty()) "Any set" else "${q.sets.size} selected · any one", style = zen(11.sp, color = C.muted))
        CardGrid(model.content.sets.filter { nameMatches(it.name, query) }, columns = 4, gap = 7.dp) { s, m ->
            val on = s.key in q.sets
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (on) C.lightPink else C.card).border(2.dp, if (on) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { model.updatePotentialQuery(q.copy(sets = if (on) q.sets - s.key else q.sets + s.key)) }.padding(horizontal = 4.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SetIcon(s.icon, 27.dp); VSpace(6.dp); FitText(s.name, zen(10.sp))
            }
        }
    }
}

@Composable
internal fun PotentialMainsSheet(model: AppModel) {
    val q = model.potentialQuery
    SheetBody("Main stats", trailing = { LinkText("Any main") { model.updatePotentialQuery(q.copy(mains = emptySet())) } }, button = "Done", onButton = { model.sheet = null }) {
        CardGrid(GearStat.entries, columns = 3) { t, m -> PotentialChoice(t.short, m, t in q.mains) {
            model.updatePotentialQuery(q.copy(mains = if (t in q.mains) q.mains - t else q.mains + t)) } }
    }
}

@Composable
internal fun PotentialOptionsSheet(model: AppModel) {
    val q = model.potentialQuery
    SheetBody("Finishing options", button = "Done", onButton = { model.sheet = null }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Include reforging", style = zen(13.sp)); Text("After +15, where available", style = zen(11.sp, FontWeight.Medium, C.muted)) }
                Toggle(q.reforge) { model.updatePotentialQuery(q.copy(reforge = it)) }
            }
        }
        SectionTitle("Pieces to show")
        Segmented(listOf("Upgrades", "Unfinished", "All gear"), q.show) { model.updatePotentialQuery(q.copy(show = it)) }
        Text("Upgrades excludes pieces that already meet the target.", style = zen(11.sp, FontWeight.Medium, C.muted))
        SectionTitle("Chance")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("At least", style = zen(13.sp), modifier = Modifier.weight(1f))
            PotentialNumber(q.minimumChance, "Minimum chance", Modifier.width(84.dp), 0.0, 100.0) { model.updatePotentialQuery(q.copy(minimumChance = it)) }
            Text("%", style = zen(13.sp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include rare-roll possibilities", style = zen(12.sp), modifier = Modifier.weight(1f))
            Toggle(q.rareOnly) { model.updatePotentialQuery(q.copy(rareOnly = it)) }
        }
        LinkText("Reset to Speed 20, no boots") { model.updatePotentialQuery(PotentialQuery()) }
    }
}

@Composable
internal fun PotentialHelpSheet(model: AppModel) {
    SheetBody("About gear potential", button = "Got it", onButton = { model.sheet = null }) {
        SectionTitle("Your target")
        Text("Minimums apply to substats on one piece. The main stat is a separate filter.", style = zen(13.sp, FontWeight.Medium))
        SectionTitle("The chance")
        Text("The estimate counts the remaining rolls together, so two stats compete for the same upgrades. Percentages use usual roll ranges. Rare maximum rolls are shown separately, without an estimated percentage.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Reforging")
        Text("Counts reforge gains only on eligible level 85 gear. Level 88 and 90 gear gets no extra reforge gain.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Coverage")
        Text("Unfinished forecasts cover level 85 and 88 Epic and Heroic pieces. Finished pieces can also match now. No modification stones are assumed.", style = zen(12.sp, FontWeight.Medium))
        model.potentialResult?.takeIf { it.unsupported > 0 }?.let { Text("${it.unsupported} older or unsupported unfinished pieces were left out of this search.", style = zen(12.sp, FontWeight.Medium, C.muted)) }
    }
}

@Composable
internal fun PotentialPieceSheet(model: AppModel, id: Long) {
    val result = model.potentialResult ?: return
    val match = result.matches.firstOrNull { it.item.id == id } ?: return
    val item = match.item
    SheetBody("Equipment", button = "Track over game", onButton = {
        model.sheet = null
        model.trackPotential(match, openOverlay = true)
    }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) { GearSearchIdentity(model, item, result.query.targets.keys) }
        Card(shadow = C.pink, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (match.outcome.rareOnly) "Rare" else match.outcome.label(), style = dela(28.sp, C.blue))
                Column(Modifier.weight(1f)) { Text(finishAction(match, result.query), style = zen(13.sp)); Text("Chance to meet every target", style = zen(11.sp, FontWeight.Medium, C.muted)) }
            }
        }
        model.inventoryPosition(item.id)?.let { pos ->
            Card(shadow = null, padding = PaddingValues(12.dp)) {
                Text(pos.label(), style = zen(13.sp, color = C.blue))
                Text(pos.grid() + " · 5 columns", style = zen(12.sp))
                Text(model.inventoryOrderLabel, style = zen(11.sp, FontWeight.Medium, C.muted))
                LinkText("Match my game view") { open(model, SheetKind.INVENTORY_ORDER) }
            }
        }
        SectionTitle("Now / target")
        result.query.goal().minimums.forEach { (t, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatIcon(t.stat, 16.dp)
                Text(if (t.percentOfBase) t.stat.label + " %" else t.stat.label, style = zen(12.sp), modifier = Modifier.weight(1f))
                Text("${t.format(item.subs.firstOrNull { it.type == t }?.value ?: 0.0)} / ${t.format(v)}", style = zen(13.sp, color = C.blue))
            }
        }
        Text(gearOwner(model, item), style = zen(11.sp, FontWeight.Medium, C.muted))
        LinkText("How the chance is calculated") { open(model, SheetKind.POTENTIAL_HELP) }
    }
}

@Composable
private fun PotentialChoice(text: String, modifier: Modifier, selected: Boolean, click: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.heightIn(min = 44.dp).clip(shape).background(if (selected) C.lightPink else C.card).border(2.dp, if (selected) C.edge else C.line, shape)
        .tap(click).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { FitText(text, zen(12.sp)) }
}

@Composable
internal fun PotentialNumber(value: String, hint: String, modifier: Modifier, minimum: Double, maximum: Double, changed: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    NumberValue(value.toDoubleOrNull() ?: Double.NaN, hint, minimum, maximum, modifier.height(42.dp).clip(shape).background(C.field).border(1.5.dp, C.line, shape)) {
        changed(if (it.isNaN()) "" else fmtNum(it))
    }
}

@Composable
internal fun GearPositionLabel(model: AppModel, item: Item, explicitGameScore:Boolean=false) {
    if (!item.ghost) {
        val position = model.inventoryPosition(item.id)
        Text(position?.label()?.let { if(explicitGameScore)it.replace(" · score "," · game ES ") else it } ?: if (model.inventoryBagOnly && item.equippedOn != null) "Equipped · outside bag view" else "Position unavailable", style = zen(10.sp, FontWeight.Medium, C.blue),
            modifier = Modifier.tap { model.sheet = Sheet(SheetKind.INVENTORY_ORDER, 0) })
    }
}

@Composable
internal fun InventoryOrderSheet(model: AppModel) {
    SheetBody("Find gear in game", button = "Done", onButton = { model.sheet = null }) {
        Text("Sort your in-game inventory by Equipment Score.", style = zen(13.sp))
        SectionTitle("Direction")
        Segmented(listOf("Low score first", "High score first"), if (model.inventoryAscending) 0 else 1) { model.setInventoryOrder(it == 0, model.inventoryBagOnly) }
        SectionTitle("Equipment shown")
        Segmented(listOf("All gear", "In bag only"), if (model.inventoryBagOnly) 1 else 0) { model.setInventoryOrder(model.inventoryAscending, it == 1) }
        Card(shadow = null, padding = PaddingValues(12.dp)) {
            Text("Inventory # is the piece's position in that list.", style = zen(13.sp, color = C.blue))
            VSpace(6.dp)
            Text("Turn off slot, set and other filters in the game. All gear includes equipped pieces. Search filters here do not change inventory numbers.", style = zen(12.sp, FontWeight.Medium, C.muted))
        }
        Text("Positions use your latest scan, excluding pieces marked cleared. A ~ marks an estimate after manual upgrades or an import without full sorting data; rescan for exact tie-breaks. Rescan after selling or reforging. Row and column hints assume 5 columns.", style = zen(12.sp, FontWeight.Medium, C.muted))
    }
}
