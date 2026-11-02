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

@Composable
internal fun PotentialTargetsSheet(model: AppModel) {
    val q = model.f.query
    var adding by remember { mutableStateOf(false) }
    SheetBody("Substat targets", button = "Done", onButton = { model.sheet = null }) {
        Text("All targets on one piece.", style = zen(12.sp, FontWeight.Medium, C.muted))
        q.targets.forEach { (t, value) ->
            val limits = e7.gearscan.opt.GearBounds.target(t, q.reforge, q.show == 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatIcon(t.stat, 18.dp)
                Text(t.short, style = zen(13.sp), modifier = Modifier.width(52.dp))
                PotentialNumber(value, "Minimum ${t.short}", Modifier.weight(1f), limits.minimum, limits.maximum) { model.f.edit(q.copy(targets = q.targets + (t to it))) }
                Text(if (t.showsPercent) "%+" else "+", style = zen(12.sp, color = C.muted))
                Box(Modifier.size(38.dp).tap { model.f.edit(q.copy(targets = q.targets - t)) }, contentAlignment = Alignment.Center) { Text("×", style = dela(16.sp)) }
            }
        }
        if (q.targets.size < 4) OutlineButton("Add substat", Modifier.fillMaxWidth(), height = 42.dp) { adding = !adding }
        if (adding) CardGrid(GearStat.entries.filter { it !in q.targets }, columns = 3, gap = 7.dp) { t, m ->
            PotentialChoice(t.short, m, false) { model.f.edit(q.copy(targets = q.targets + (t to ""))); adding = false }
        }
    }
}

@Composable
internal fun PotentialSlotsSheet(model: AppModel) {
    val q = model.f.query
    SheetBody("Equipment slots", trailing = { LinkText("All") { model.f.edit(q.copy(slots = Slot.entries.toSet())) } }, button = "Done", onButton = { model.sheet = null }) {
        CardGrid(Slot.entries, columns = 3) { s, m ->
            val selected = s in q.slots
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (selected) C.lightPink else C.card).border(2.dp, if (selected) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { model.f.edit(q.copy(slots = if (selected) q.slots - s else q.slots + s)) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GearIcon(s.icon, 30.dp, C.ink); VSpace(8.dp); FitText(s.label, zen(12.sp))
            }
        }
        LinkText("Everything except boots") { model.f.edit(q.copy(slots = Slot.entries.toSet() - Slot.BOOTS)) }
    }
}

@Composable
internal fun PotentialSetsSheet(model: AppModel) {
    val q = model.f.query
    var query by remember { mutableStateOf("") }
    SheetBody("Sets", trailing = { LinkText("Any set") { model.f.edit(q.copy(sets = emptySet())) } }, button = "Done", onButton = { model.sheet = null }) {
        NameFilter(query, "Name") { query = it }
        Text(if (q.sets.isEmpty()) "Any set" else "${q.sets.size} selected · any one", style = zen(11.sp, color = C.muted))
        CardGrid(model.graph.content.content.sets.filter { nameMatches(it.name, query) }, columns = 4, gap = 7.dp) { s, m ->
            val on = s.key in q.sets
            Column(m.clip(RoundedCornerShape(12.dp)).background(if (on) C.lightPink else C.card).border(2.dp, if (on) C.edge else C.line, RoundedCornerShape(12.dp))
                .tap { model.f.edit(q.copy(sets = if (on) q.sets - s.key else q.sets + s.key)) }.padding(horizontal = 4.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SetIcon(s.icon, 27.dp); VSpace(6.dp); FitText(s.name, zen(10.sp))
            }
        }
    }
}

@Composable
internal fun PotentialMainsSheet(model: AppModel) {
    val q = model.f.query
    SheetBody("Main stats", trailing = { LinkText("Any main") { model.f.edit(q.copy(mains = emptySet())) } }, button = "Done", onButton = { model.sheet = null }) {
        CardGrid(GearStat.entries, columns = 3) { t, m -> PotentialChoice(t.short, m, t in q.mains) {
            model.f.edit(q.copy(mains = if (t in q.mains) q.mains - t else q.mains + t)) } }
    }
}

@Composable
internal fun PotentialOptionsSheet(model: AppModel) {
    val q = model.f.query
    SheetBody("Finishing options", button = "Done", onButton = { model.sheet = null }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Include reforging", style = zen(13.sp)); Text("After +15, where available", style = zen(11.sp, FontWeight.Medium, C.muted)) }
                Toggle(q.reforge) { model.f.edit(q.copy(reforge = it)) }
            }
        }
        SectionTitle("Pieces to show")
        Segmented(listOf("Upgrades", "Unfinished", "All gear"), q.show) { model.f.edit(q.copy(show = it)) }
        Text("Upgrades excludes pieces that already meet the target.", style = zen(11.sp, FontWeight.Medium, C.muted))
        SectionTitle("Chance")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("At least", style = zen(13.sp), modifier = Modifier.weight(1f))
            PotentialNumber(q.minimumChance, "Minimum chance", Modifier.width(84.dp), 0.0, 100.0) { model.f.edit(q.copy(minimumChance = it)) }
            Text("%", style = zen(13.sp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include rare-roll possibilities", style = zen(12.sp), modifier = Modifier.weight(1f))
            Toggle(q.rareOnly) { model.f.edit(q.copy(rareOnly = it)) }
        }
        LinkText("Reset to Speed 20, no boots") { model.askReset("Reset the search?", "Targets and filters go back to Speed 20, no boots.") { model.f.edit(PotentialQuery()) } }
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
        model.f.result?.takeIf { it.unsupported > 0 }?.let { Text("${it.unsupported} older or unsupported unfinished pieces were left out of this search.", style = zen(12.sp, FontWeight.Medium, C.muted)) }
    }
}

@Composable
internal fun PotentialPieceSheet(model: AppModel, id: Long) {
    val result = model.f.result ?: return
    val match = result.matches.firstOrNull { it.item.id == id } ?: return
    val item = match.item
    SheetBody("Equipment", button = "Track over game", onButton = {
        model.sheet = null
        model.show(model.f.track(match, openOverlay = true))
    }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) { GearSearchIdentity(model, item, result.query.targets.keys) }
        Card(shadow = C.pink, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (match.outcome.rareOnly) "Rare" else match.outcome.label(), style = dela(28.sp, C.blue))
                Column(Modifier.weight(1f)) { Text(finishAction(match, result.query), style = zen(13.sp)); Text("Chance to meet every target", style = zen(11.sp, FontWeight.Medium, C.muted)) }
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
