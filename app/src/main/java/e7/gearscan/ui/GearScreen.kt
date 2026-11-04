package e7.gearscan.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Screen

/** The Gear tab: the three gear modes, one tap each. */
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
        ModeCard("Sell review", "Sell or keep each piece, with its ES now and at +15.") { graph.nav.go(Screen.SellFilters) }
        ModeCard("Improve heroes", "Unfinished pieces that would make a hero you've set up better once enhanced.") { graph.nav.go(Screen.HeroUpgrades()) }
        ModeCard("Find gear", "Pieces that can still reach the substats you ask for, with the chance.") { graph.nav.go(Screen.GearPotential) }
        if (graph.marks.kept.isNotEmpty())
            RowCard("Kept pieces", { graph.nav.go(Screen.SellKept) }) { Text("${graph.marks.kept.size} kept", style = zen(12.sp)) }
    }
}

@Composable
private fun ModeCard(title: String, about: String, open: () -> Unit) {
    Card(onClick = open, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = dela(20.sp), modifier = Modifier.weight(1f))
            Text("›", style = dela(20.sp))
        }
        VSpace(4.dp)
        Text(about, style = zen(12.sp, color = C.muted))
    }
}
