package e7.gearscan.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Screen
import e7.gearscan.data.*

@Composable
fun UpgradeScreen(model: AppModel) {
    val box = model.box ?: return
    val t = model.upgrades
    val selected = t.record?.takeIf { it.scan == model.lastScan && it.item.id in box.itemsById }
    var choosing by remember { mutableStateOf(selected == null) }
    var set by remember { mutableStateOf<String?>(null) }
    var choosingSet by remember { mutableStateOf(false) }
    var slot by remember { mutableStateOf<Slot?>(null) }
    var count by remember(set, slot) { mutableStateOf(20) }
    Frame(null, null, actions = {
        PinkButton(if (choosing) "Use selected piece" else "Track over game", Modifier.weight(1f), enabled = selected != null) {
            if (choosing) choosing = false else model.openUpgradeOverlay()
        }
    }) {
        BackToPrevious(model)
        ScreenTitle("Track upgrades")
        if (t.queue.isNotEmpty()) RowCard("Gear list", { model.go(when { t.scoreQuery!=null -> Screen.SellFilters; t.queueSource == "heroes" -> Screen.HeroUpgrades(); else -> Screen.GearPotential }) }) {
            Text(if(t.sellQueueComplete)"List complete" else "${t.queueIndex + 1} of ${t.pendingQueue.size} ${if(t.isSellQueue)"remaining" else "matches"}", style = zen(12.sp))
        }
        if(t.needsRollRefresh)Card(shadow=null,padding=PaddingValues(12.dp)) {
            Text("Refresh the roll ranking",style=dela(16.sp))
            Text("Starting rolls now count too. This saved list still has its old order and filters.",style=zen(12.sp))
            LinkText("Update Sell filters") { model.go(Screen.SellFilters) }
        }
        if(t.sellQueueComplete)Card(shadow=C.pink,padding=PaddingValues(14.dp)) {
            Text("List complete",style=dela(18.sp));VSpace(7.dp)
            Text("All pieces in this list are marked cleared.",style=zen(12.sp))
            if(t.canUndoSold)LinkText("Undo sold mark") { model.undoOverlaySold() }
        }else if (!choosing && selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Your piece", modifier = Modifier.weight(1f))
                Pill("Change", onClick = { choosing = true })
            }
            Card(shadow = null, padding = PaddingValues(12.dp)) {
                GearSearchIdentity(model, selected.item, t.goal?.minimums?.keys ?: emptySet())
                VSpace(10.dp)
                Text(gearOwner(model, selected.item), style = zen(11.sp, FontWeight.Medium, C.muted))
            }
            Card(shadow = C.pink, padding = PaddingValues(14.dp)) {
                t.scoreGoal?.let { goal->Text(goal.label(),style=dela(18.sp));Text(goal.reference,style=zen(11.sp,color=C.muted));VSpace(6.dp) }
                t.goal?.let { goal ->
                    Text(goal.label(), style = dela(18.sp))
                    VSpace(6.dp)
                }
                Text(t.title, style = zen(13.sp, color = C.blue))
                VSpace(6.dp)
                if (!t.hasTarget && !t.busy) t.results.filter { it.current || it.hits.isNotEmpty() }.sortedByDescending { it.hits.size }.take(4).forEach { h ->
                    Text(h.name + listOfNotNull(if (h.current) "an upgrade now ${h.nowGain}".trim() else null,
                        if (h.hits.isNotEmpty()) "${h.hits.size * 100 / h.total}% of finishes do better, usually ${h.typical}" else null).joinToString(" · ", " · "),
                        style = zen(12.sp))
                    if (h.hits.isNotEmpty()) Text(if (h.hits.size == h.total) "An upgrade whatever the rolls" else h.minimal.firstOrNull()?.let { "Upgrade at +15 from: $it" }.orEmpty(),
                        style = zen(11.sp, FontWeight.Medium, C.muted))
                    VSpace(4.dp)
                }
                if (!t.hasTarget && selected.item.enhance == 15) {
                    if (selected.item.level == 85 && t.canReforge?.invoke(selected.item.id) == true) LinkText("Record the reforge") { t.reforge() }
                    if (t.equippedOn == null) t.equipHeroes.forEach { h -> LinkText("Record equipped on ${h.name}") { t.equipOn?.invoke(h.id) } }
                    else LinkText("Undo: equipped on ${t.equippedOn}") { t.undoEquip?.invoke() }
                    VSpace(6.dp)
                }
                Text(if(t.isRollReview)"Record each upgrade to update its actual roll history." else if (t.hasTarget) "Record each upgrade to see your chance change." else "Checks how this piece could help your saved hero builds.", style = zen(12.sp, FontWeight.Medium, C.muted))
                VSpace(12.dp)
                if (t.queueSource == "heroes" && !t.hasTarget) LinkText("Back to Improve heroes") { model.go(Screen.HeroUpgrades()) }
                else LinkText(if(t.scoreQuery!=null) "Change filters" else "Change targets") { model.go(if(t.scoreQuery!=null)Screen.SellFilters else Screen.GearPotential) }
            }
            SectionTitle("Progress", trailing = "${selected.history.size - 1} steps")
            Card(shadow = null, padding = PaddingValues(12.dp)) {
                selected.history.takeLast(4).forEachIndexed { i, piece ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("+${piece.enhance}", style = zen(12.sp, color = C.blue), modifier = Modifier.width(34.dp))
                        Text(if (selected.history.size == 1) "Starting from your scan" else piece.subs.joinToString(" · ") { "${it.type.short} ${it.type.format(it.value)}" }, style = zen(11.sp, FontWeight.Medium, C.muted))
                    }
                }
            }
            t.notice?.let { Text(it, style = zen(11.sp, FontWeight.Medium, C.blue)) }
            Text(if(t.isSellQueue)"Sell in Epic Seven first, then tap Sold → next in the overlay. Undo mark restores only the helper's mark." else "Open the floating panel over the game, enhance your piece, then record the totals at each +3.", style = zen(12.sp, FontWeight.Medium, C.muted))
            LinkText("Find another piece by final stats") { model.go(Screen.GearPotential) }
        } else {
            Text("Choose a piece to follow as you upgrade.", style = zen(12.sp, FontWeight.Medium, C.muted))
            RowCard("Looking for", { model.go(Screen.GearPotential) }) { Text("A final stat target", style = zen(12.sp)) }
            RowCard("Set", { choosingSet = !choosingSet }) { Text(set?.let { model.content.set(it)?.name } ?: "Any set", style = zen(12.sp)) }
            if (choosingSet) {
                LinkText("Any set") { set = null; choosingSet = false }
                CardGrid(model.content.sets, columns = 4, gap = 6.dp) { s, m ->
                    Pill(s.name, modifier = m, selected = set == s.key, onClick = { set = s.key; choosingSet = false })
                }
            }
            CardGrid(listOf<Slot?>(null) + Slot.entries, columns = 3, gap = 6.dp) { s, m ->
                Pill(s?.label ?: "All", selected = slot == s, modifier = m, onClick = { slot = s })
            }
            val items = box.items.filter { it.id !in model.cleanupDone && UpgradeRecord.supported(it) && (it.enhance < 15 || t.records[it.id]?.scan == model.lastScan) && (slot == null || it.slot == slot) }
                .filter { set == null || it.set == set }
                .sortedWith(compareByDescending<Item> { t.records[it.id]?.scan == model.lastScan }.thenBy { it.slot.ordinal }.thenByDescending { it.enhance })
            SectionTitle("Your gear", trailing = "${items.size}")
            items.take(count).forEach { original ->
                val item = t.records[original.id]?.takeIf { it.scan == model.lastScan }?.item ?: UpgradeRecord.fromScan(original)
                Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp)) {
                    Column(Modifier.tap { model.selectUpgrade(original.id); choosing = false }) {
                        GearSearchIdentity(model, item)
                        VSpace(8.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(gearOwner(model, item), style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
                            Text(if (item.id == selected?.item?.id) "Selected" else "Choose piece", style = zen(10.sp, color = C.blue))
                            Spacer(Modifier.width(7.dp)); ChevronSlot(true)
                        }
                    }
                }
            }
            if (items.isEmpty()) Text("No matching gear. Try another slot or search.", style = zen(12.sp, FontWeight.Medium, C.muted))
            if (count < items.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
        }
    }
}
