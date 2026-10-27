package e7.gearscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import e7.gearscan.AppModel
import e7.gearscan.data.OwnedHero

class HeroFilter {
    var query by mutableStateOf("")
    var element by mutableStateOf<String?>(null)
    var role by mutableStateOf<String?>(null)

    val active get() = query.isNotBlank() || element != null || role != null

    fun matches(model: AppModel, h: OwnedHero): Boolean {
        if (query.isNotBlank() && !nameMatches(model.heroName(h), query)) return false
        val g = model.gameHero(h)
        return (element == null || g?.element == element) && (role == null || g?.role == role)
    }
}

private val ELEMENTS = listOf("fire" to "Fire", "ice" to "Ice", "wind" to "Earth", "light" to "Light", "dark" to "Dark")
private val CLASSES = listOf("warrior" to "Warrior", "knight" to "Knight", "assassin" to "Thief", "ranger" to "Ranger",
    "mage" to "Mage", "manauser" to "Soul Weaver")

@Composable
fun HeroFilterBar(f: HeroFilter) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NameFilter(f.query, "Name") { f.query = it }
        TraitRow("Element", ELEMENTS, f.element, { "icons/elements/element-$it.png" }) { f.element = it }
        TraitRow("Class", CLASSES, f.role, { "icons/classes/class-$it.png" }) { f.role = it }
        val any = f.element != null || f.role != null
        Text("Clear class / element", style = zen(12.sp, color = if (any) C.blue else C.dash).copy(textDecoration = TextDecoration.Underline),
            modifier = Modifier.then(if (any) Modifier.tap { f.element = null; f.role = null } else Modifier))
    }
}

@Composable
private fun SearchGlyph() {
    Canvas(Modifier.size(16.dp)) {
        val w = 2.dp.toPx()
        val r = size.minDimension * 0.34f
        val c = Offset(r + w / 2, r + w / 2)
        drawCircle(C.ink, r, c, style = Stroke(w))
        drawLine(C.ink, Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(size.width - w / 2, size.height - w / 2), w, StrokeCap.Round)
    }
}

@Composable
private fun TraitRow(label: String, options: List<Pair<String, String>>, selected: String?, icon: (String) -> String,
                     onSelect: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = zen(13.sp), modifier = Modifier.padding(end = 6.dp))
        TraitChip(selected == null, ring = false, bg = if (selected == null) C.pink else C.field, onClick = { onSelect(null) }) {
            Text("All", style = zen(11.sp, FontWeight.Black, if (selected == null) C.onAccent else C.ink))
        }
        options.forEach { (key, _) ->
            TraitChip(selected == key, ring = true, bg = C.iconBg, onClick = { onSelect(if (selected == key) null else key) }) {
                AsyncImage(gameImage(icon(key)), null, Modifier.size(18.dp))
            }
        }
    }
}

/** Site style: 2 dp ink circle; a picked icon gets a card gap and a pink ring around it. */
@Composable
private fun TraitChip(on: Boolean, ring: Boolean, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(38.dp).then(if (on && ring) Modifier.border(3.dp, C.pink, CircleShape) else Modifier).padding(5.dp)
        .clip(CircleShape).background(bg).border(2.dp, C.edge, CircleShape).tap(onClick), contentAlignment = Alignment.Center) { content() }
}
