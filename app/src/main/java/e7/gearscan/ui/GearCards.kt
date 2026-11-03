package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.data.*

internal fun pieceName(graph: AppGraph, item: Item) = "${graph.content.content.set(item.set)?.name ?: item.set.removeSuffix("Set")} ${item.slot.label}"

/** The piece's name over the game's inventory cell: tile, then main stat and substats in one column. [side] sits right of the stats. */
@Composable
internal fun GearSearchIdentity(graph: AppGraph, original: Item, highlight: Set<GearStat> = emptySet(), explicitGameScore: Boolean = false,
                                owner: Boolean = true, showEs: Boolean = true, side: (@Composable ColumnScope.() -> Unit)? = null,
                                trailing: (@Composable () -> Unit)? = null) {
    val item = if (original.enhance < 15) UpgradeRecord.fromScan(original) else original
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pieceName(graph, item), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GearTile(item, 46.dp, setIcon = graph.content.content.set(item.set)?.icon)
            StatStack(item, highlight = highlight)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                side?.invoke(this)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lv.${item.level}", style = zen(10.sp, FontWeight.Medium, C.muted))
                    if (showEs) GearPositionLabel(graph, item, explicitGameScore)
                }
                if (owner) item.equippedOn?.let { id -> OwnerTag(graph.heroes.hero(id)?.let(graph.heroes::heroName) ?: "hero not in scan") }
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
internal fun GearPositionLabel(graph: AppGraph, item: Item, explicitGameScore: Boolean = false) {
    if (!item.ghost) Text("ES ${InventoryOrder.score(item)}", style = zen(10.sp, FontWeight.Medium, C.blue))
}

/** One line per target: the stat on the piece now → the target, with a bar that fills as it gets there. */
@Composable
internal fun GoalLines(item: Item, minimums: Map<GearStat, Double>, size: Int = 11, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        minimums.forEach { (t, want) ->
            val sub = item.subs.firstOrNull { it.type == t }
            val now = sub?.value ?: 0.0
            val met = sub != null && now + 1e-9 >= want
            val color = if (met) C.blue else C.ink
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StatIcon(t.stat, size.dp, color)
                // past the target, "22 → 20" would read as going down; a missing line is a dash, not a zero
                Text(when { met -> "${t.format(now)} · met"; sub == null -> "— → ${t.format(want)}"; else -> "${t.format(now)} → ${t.format(want)}" },
                    style = zen(size.sp, FontWeight.Bold, color), softWrap = false)
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                    Box(Modifier.fillMaxWidth((now / want).coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(C.blue))
                }
            }
        }
        note?.let { Text(it, style = zen((size - 1).sp, FontWeight.Medium, C.muted)) }
    }
}

/** Where the player switches the game's filters: the set, slot and level of the pieces under it, and how many there are. */
@Composable
internal fun GroupHeader(graph: AppGraph, item: Item, count: Int) {
    val set = graph.content.content.set(item.set)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val nameWidth = maxWidth / 2
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SetIcon(set?.icon, 18.dp)
            Text(set?.name ?: item.set.removeSuffix("Set"), style = dela(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = nameWidth))
            GearIcon(item.slot.icon, 16.dp, C.ink)
            Text("Lv.${item.level}", style = zen(12.sp, FontWeight.Medium, C.muted), softWrap = false)
            Box(Modifier.weight(1f).height(6.dp).drawBehind {
                val w = 2.dp.toPx()
                drawRect(C.edge, size = Size(size.width, w))
                drawRect(C.edge, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
            })
            Text("$count", style = zen(11.sp, FontWeight.Bold, C.muted))
        }
    }
}

/** [shown] with a [GroupHeader] where the set, slot or level changes; the counts are over [all]. */
@Composable
internal fun <T> GroupedRows(graph: AppGraph, grouped: Boolean, shown: List<T>, all: List<T>, item: (T) -> Item?, row: @Composable (T) -> Unit) {
    val counts = remember(all, grouped) { if (grouped) all.mapNotNull(item).groupingBy(InventoryOrder::group).eachCount() else emptyMap() }
    var last: Any? = null
    shown.forEach { r ->
        val i = item(r)
        if (grouped && i != null && InventoryOrder.group(i) != last) {
            last = InventoryOrder.group(i)
            GroupHeader(graph, i, counts[last] ?: 0)
        }
        row(r)
    }
}
