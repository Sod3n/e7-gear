package e7.gearscan.features.sellreview

import e7.gearscan.ui.*

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.data.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SellKeptScreen(model: AppModel) {
    var slots by rememberSaveable { mutableStateOf(emptySet<Slot>()) }
    var sets by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var count by rememberSaveable { mutableIntStateOf(30) }
    val box = model.graph.scan.box
    // kept keeps the order pieces were kept in
    val all = model.graph.marks.kept.toList().mapNotNull { box?.itemsById?.get(it) }
    fun es(item: Item) = InventoryOrder.score(item)
    val shown = all.filter { (slots.isEmpty() || it.slot in slots) && (sets.isEmpty() || it.set in sets) }.let { list ->
        when (sort) { 0 -> list.asReversed(); 1 -> list.sortedBy(::es); else -> list.sortedByDescending(::es) }
    }
    Frame(model, null) {
        BackToPrevious(); ScreenTitle("Kept pieces")
        Text("${all.size} kept", style = zen(12.sp, color = C.muted))
        CardGrid(Slot.entries, columns = 3, gap = 6.dp) { s, m ->
            Pill(s.label, m, selected = s in slots, onClick = { slots = if (s in slots) slots - s else slots + s; count = 30 },
                leading = { GearIcon(s.icon, 14.dp, if (s in slots) C.onAccent else C.ink) })
        }
        val keptSets = all.map { it.set }.distinct().sortedBy { model.graph.content.content.set(it)?.name ?: it }
        if (keptSets.size > 1) CardGrid(keptSets, columns = 3, gap = 6.dp) { k, m ->
            val set = model.graph.content.content.set(k)
            Pill(set?.name ?: k.removeSuffix("Set"), m, selected = k in sets, onClick = { sets = if (k in sets) sets - k else sets + k; count = 30 },
                leading = { SetIcon(set?.icon, 14.dp) })
        }
        Segmented(listOf("Newest", "Lowest ES", "Highest ES"), sort) { sort = it }
        if (shown.isEmpty()) Card { Text(if (all.isEmpty()) "Nothing kept yet. Tap Keep in Sell review." else "No kept piece matches these filters.", style = zen(13.sp)) }
        shown.take(count).forEach { item ->
            val view = if (item.enhance < 15) UpgradeRecord.fromScan(item) else item
            Card(shadow = null, padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GearTile(view, 40.dp, setIcon = model.graph.content.content.set(view.set)?.icon)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${model.graph.content.content.set(view.set)?.name ?: view.set.removeSuffix("Set")} ${view.slot.label}", style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text("ES ${es(item)}", style = dela(13.sp))
                        }
                        MainStatLine(view)
                        GearSearchSubs(view)
                    }
                }
                VSpace(6.dp)
                OutlineButton("Unlock", Modifier.fillMaxWidth(), height = 36.dp) { model.graph.marks.unmark(item.id) }
            }
        }
        if (count < shown.size) OutlineButton("Show 30 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 30 }
    }
}
