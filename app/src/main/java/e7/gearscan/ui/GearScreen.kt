package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Screen

/** The Gear tab: the three gear modes and the over-game tracker, one tap each. */
@Composable
fun GearScreen(graph: AppGraph) {
    Frame(graph, Screen.Gear) {
        ScreenTitle("Gear", "ソウビ")
        if (graph.scan.box == null) {
            Card {
                Text("Import your gear first", style = dela(17.sp)); VSpace(6.dp)
                Text("Scan the game or restore a backup in Scan & data.", style = zen(12.sp, color = C.muted))
                VSpace(10.dp); OutlineButton("Scan & data", Modifier.fillMaxWidth(), height = 44.dp) { graph.nav.go(Screen.ScanData) }
            }
            return@Frame
        }
        val sell = graph.sellReview.result?.let { r -> r.matches.count { it.item.id !in graph.marks.reviewed } }
        ModeCard("Sell review", "Worst rolls first. Sell or keep each piece, with its ES now and at +15.",
            listOfNotNull(sell?.let { "$it to review" }, graph.marks.kept.size.takeIf { it > 0 }?.let { "$it kept" }).joinToString(" · ").ifEmpty { "Not checked yet" }) {
            graph.nav.go(Screen.SellFilters)
        }
        val heroes = graph.improveHeroes.heroUpgrades?.takeIf { !it.running && it.result != null && !graph.improveHeroes.stale }?.result
            ?.let { r -> graph.improveHeroes.view(r).count { it.rows.isNotEmpty() } }
        ModeCard("Improve heroes", "Unfinished pieces that would make a hero you've set up better once enhanced.",
            heroes?.let { "$it heroes can get better" } ?: "Not checked yet") { graph.nav.go(Screen.HeroUpgrades()) }
        val found = graph.findGear.result?.matches?.size
        ModeCard("Find gear", "Pieces that can still reach the substats you ask for, with the chance.",
            found?.let { "${e7.gearscan.value.Count(it).of("piece")} found last time" } ?: "Choose a target") { graph.nav.go(Screen.GearPotential) }
        if (graph.tracker.queue.isNotEmpty() || graph.tracker.record != null)
            RowCard("Over the game", { graph.nav.go(Screen.Upgrade) }) { Text("Continue tracking", style = zen(12.sp)) }
        if (graph.marks.kept.isNotEmpty())
            RowCard("Kept pieces", { graph.nav.go(Screen.SellKept) }) { Text("${graph.marks.kept.size} kept", style = zen(12.sp)) }
    }
}

@Composable
private fun ModeCard(title: String, about: String, status: String, open: () -> Unit) {
    Card(onClick = open, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = dela(20.sp), modifier = Modifier.weight(1f))
            Text("›", style = dela(20.sp))
        }
        VSpace(4.dp)
        Text(about, style = zen(12.sp, color = C.muted))
        VSpace(10.dp)
        val shape = RoundedCornerShape(999.dp)
        Box(Modifier.clip(shape).background(C.blue.copy(alpha = .12f)).border(1.5.dp, C.blue, shape).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(status, style = zen(12.sp, color = C.blue))
        }
    }
}
