package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Screen
import e7.gearscan.overlay.OverlayService
import e7.gearscan.overlay.OverlayState
import e7.gearscan.opt.Cleanup

/** Make room: pieces that can be fed or sold, why each can go, and ticks for the ones done. */
@Composable
fun CleanupScreen(model: AppModel) {
    val box = model.box ?: return
    val c = model.cleanup
    val result = c?.takeIf { !it.running && it.finishedAt > 0 }
    Frame(null, null, actions = {
        when {
            c?.running == true -> OutlineButton("Stop", Modifier.weight(1f)) { model.stopCleanup() }
            result == null -> PinkButton(if (model.cleanupResumable) "Continue the check" else "Check my gear", Modifier.weight(1f),
                enabled = model.pool?.running != true) { model.startCleanup() }
            else -> {
                OutlineButton(if (model.cleanupResumable) "Continue" else "Check again", Modifier.weight(1f)) { model.startCleanup() }
                PinkButton("Checklist", Modifier.weight(1f), enabled = result.picks.any { it.item.id !in model.cleanupDone }) {
                    // pieces are fed and sold in the game, so the list goes over it; its last step marks them done here
                    if (model.loadCleanupChecklist()) {
                        model.startLoadedOverGame()
                    }
                }
            }
        }
    }) {
        BackToPrevious(model)
        ScreenTitle("Make room", "セイリ")
        LinkText("Sell filters") { model.go(Screen.SellFilters) }
        LinkText("Pieces that could improve your heroes") { model.go(Screen.HeroUpgrades()) }
        LinkText("Search gear by final stats") { model.go(Screen.GearPotential) }
        LinkText("Track an upgrade over the game") { model.go(Screen.Upgrade) }
        when {
            c?.running == true -> Card(padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner()
                    Text("Checking your gear · ${if (c.total > 0) c.done * 100 / c.total else 0}%", style = zen(13.sp))
                }
                VSpace(8.dp)
                Bar(if (c.total > 0) c.done.toFloat() / c.total else 0f)
                VSpace(8.dp)
                Text("Keeps going in the game. You'll get a notification when it's done.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
            result == null -> { Intro(model); if (model.cleanupResumable) Text("The last check stopped before the end; it picks up where it was.",
                style = zen(11.sp, FontWeight.Medium, C.muted)) }
            else -> Results(model, result)
        }
    }
}

@Composable
private fun Intro(model: AppModel) {
    Card(padding = PaddingValues(12.dp)) {
        Text("Finds pieces you can feed or sell without losing gear a hero could use.", style = zen(13.sp))
        VSpace(8.dp)
        Text("· Finished pieces: only exact copies of a piece you keep.", style = zen(12.sp, FontWeight.Medium))
        VSpace(4.dp)
        Text("· Pieces below +12: only when you keep 2 better pieces of the same slot, set and main for every pair of stats it " +
            "could grow into, and none of 30 simulated finishes makes a hero with a setup better, alone or with other pieces.",
            style = zen(12.sp, FontWeight.Medium))
        VSpace(8.dp)
        Text("Gear on heroes of your pool run is never listed. The check takes a few minutes.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
    if (model.pool?.running == true) Text("Starts after the pool run.", style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
private fun Results(model: AppModel, r: e7.gearscan.CleanupState) {
    val done = model.cleanupDone
    val left = r.picks.count { it.item.id !in done }
    Card(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$left", style = dela(32.sp).pinkShadow())
            Text(if (left == 1) "piece can go" else "pieces can go", style = zen(13.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("${r.picks.size - left} done · checked ${model.scannedLabelOf(r.finishedAt)}", style = zen(11.sp, FontWeight.Medium, C.muted))
        if (r.scan != model.lastScan) {
            VSpace(6.dp)
            Text("Your gear changed since this check. Pieces that are gone dropped out; check again for new ones.",
                style = zen(11.sp, FontWeight.Medium, C.deepPink))
        }
    }
    var count by remember(r.finishedAt) { mutableStateOf(20) }
    var budget = count
    val copies = r.picks.filter { it.twin != null }
    val tickets = r.picks.filter { it.twin == null }
    if (copies.isNotEmpty() && budget > 0) {
        SectionTitle("Copies", trailing = "${copies.size}")
        copies.take(budget).forEach { p -> PickRow(model, p, "Same as the one ${where(model, p.twin!!)}") }
        budget -= copies.size
    }
    // heroes missing from the scan's hero list still wear gear in the game; they go last, together
    val worn = tickets.filter { it.item.equippedOn != null }.groupBy { p -> p.item.equippedOn!!.let { id -> model.hero(id)?.let(model::heroName) } }
    if (worn.isNotEmpty() && budget > 0) {
        SectionTitle("On heroes · unequip first", trailing = "${worn.values.sumOf { it.size }}")
        worn.entries.sortedWith(compareBy<Map.Entry<String?, List<Cleanup.Pick>>> { it.key == null }.thenBy { it.key ?: "" }).forEach { (name, ps) ->
            if (budget > 0) {
                Text(name ?: "Heroes not in the scan", style = zen(12.sp, color = C.blue))
                ps.take(budget).forEach { p -> PickRow(model, p, reason(p)) }
            }
            budget -= ps.size
        }
    }
    val bag = tickets.filter { it.item.equippedOn == null }
    if (bag.isNotEmpty() && budget > 0) {
        SectionTitle("In the bag", trailing = "${bag.size}")
        bag.sortedWith(compareBy<Cleanup.Pick> { it.item.slot.ordinal }.thenBy { it.item.set }.thenBy { it.item.enhance })
            .take(budget).forEach { p -> PickRow(model, p, reason(p)) }
    }
    if (count < r.picks.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
    if (r.picks.isEmpty()) Text("Nothing to clear: every piece is either in use, the best of its kind, or could still help a hero.",
        style = zen(13.sp, FontWeight.Medium, C.muted))
}

private fun reason(p: Cleanup.Pick) = "Kept instead for ${p.pair.joinToString(" + ") { it.short }}:"

private fun where(model: AppModel, it: e7.gearscan.data.Item) =
    it.equippedOn?.let { id -> "on " + (model.hero(id)?.let(model::heroName) ?: "a hero not in the scan") } ?: "in the bag"

@Composable
private fun PickRow(model: AppModel, p: Cleanup.Pick, reason: String) {
    val done = p.item.id in model.cleanupDone
    var open by remember(p.item.id) { mutableStateOf(false) }
    val shown = p.better.ifEmpty { listOfNotNull(p.twin) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 20.dp)) { Checkbox(done) { model.markCleaned(p.item.id, it) } }
        Column(Modifier.weight(1f).alpha(if (done) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ItemRow(model, p.item)
            // tap to see the pieces it gives way to and where they are
            Row(Modifier.tap { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reason, style = zen(11.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
                for (k in 0 until 2) Box(Modifier.height(30.dp)) {
                    shown.getOrNull(k)?.let { GearTile(it, 30.dp, setIcon = model.content.set(it.set)?.icon) }
                }
                ChevronSlot(true)
            }
            // dashed and tagged, so a kept piece never reads as one more piece to clear
            if (open) DashedBox(Modifier.fillMaxWidth(), padding = PaddingValues(8.dp)) {
                Tag("KEPT", bg = C.paper, size = 10)
                shown.forEach { VSpace(6.dp); ItemRow(model, it) }
            }
        }
    }
}

@Composable
internal fun Bar(f: Float) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(C.line)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(8.dp).clip(shape).background(C.pink))
    }
}
