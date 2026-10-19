package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import e7.gearscan.AppModel
import e7.gearscan.Screen
import e7.gearscan.ScanPhase
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.calc.Calc
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtNum
import e7.gearscan.data.fmtInt
import e7.gearscan.data.Match

@Composable
fun HeroesScreen(model: AppModel) {
    model.rev
    val box = model.box
    val f = remember { HeroFilter() }
    var open by remember { mutableStateOf<Long?>(null) }
    // the open row stays in the tier it was opened in, so it doesn't jump while the bar is dragged
    var openGroup by remember { mutableIntStateOf(0) }
    var showLow by remember { mutableStateOf(false) }
    var byMatch by remember { mutableStateOf(false) }
    Frame(model, Screen.Heroes) {
        ScreenTitle("Heroes") { ScanChip(model.scannedLabel(), model.scanPhase != ScanPhase.IDLE) { if (model.scanPhase != ScanPhase.IDLE) model.go(Screen.Capture) else model.go(Screen.ScanData) } }
        if (box == null) { Text("Scan the game to see your heroes.", style = zen(13.sp, FontWeight.Medium, C.muted)); return@Frame }
        HeroFilterBar(f)
        DashedBox(Modifier.fillMaxWidth(), color = C.blue, bg = C.ghostBg, radius = 12.dp) {
            Text(buildAnnotatedString {
                append("Tap a hero to set how much they need good gear. Searches take gear only from ")
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("lower") }
                append(" tiers, never from the same tier or higher.")
            }, style = zen(12.sp, FontWeight.Medium))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sort", style = zen(13.sp))
            Segmented(listOf("A–Z", "Lowest match"), if (byMatch) 1 else 0, height = 32.dp) { byMatch = it == 1 }
        }
        val shown = box.heroes.filter { f.matches(model, it) }
        fun group(h: OwnedHero) = if (h.id == open) openGroup else model.tier(h.id)
        val tiers = if (f.active || showLow) (5 downTo 1) else (5 downTo 3)
        Card(padding = PaddingValues(0.dp)) {
            var first = true
            for (t in tiers) {
                val hs = shown.filter { group(it) == t }.let { g ->
                    if (byMatch) g.sortedWith(compareBy<OwnedHero> { model.matchFor(it.id)?.pct ?: 101 }.thenBy { model.heroName(it) }) else g.sortedBy { model.heroName(it) }
                }
                if (hs.isEmpty()) continue
                if (!first) Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
                first = false
                Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$t", style = dela(13.sp, C.pink))
                    Text(TIER_LABELS[t - 1].uppercase(), style = zen(11.sp, color = C.muted).copy(letterSpacing = 0.6.sp))
                }
                hs.forEachIndexed { i, h ->
                    if (h.id == open) TierEditor(model, h) { open = null }
                    else HeroRow(model, h, last = i == hs.lastIndex) { open = h.id; openGroup = model.tier(h.id) }
                }
            }
            if (!f.active) {
                val low = shown.count { group(it) <= 2 }
                if (!first) Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tiers 2 and 1 · $low heroes", style = zen(12.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                    LinkText(if (showLow) "Hide" else "Show") { showLow = !showLow }
                }
            } else if (first) Text("No hero matches.", style = zen(12.sp, FontWeight.Medium, C.muted), modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun HeroRow(model: AppModel, h: OwnedHero, last: Boolean, onTap: () -> Unit) {
    val gear = model.box?.gearOf(h.id)?.values ?: emptyList()
    val t = model.tier(h.id)
    Row(Modifier.fillMaxWidth().tap(onTap).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Face(model.face(h), 36.dp, ring = if (t >= 3) C.pink else C.faint)
        val match = model.matchFor(h.id)
        LaunchedEffect(h.id) { if (match == null) model.loadPresetsQuietly(h.id, network = t >= 3) }
        // the name gets the row's width; sets and match sit under it
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(model.heroName(h), style = zen(14.sp).copy(lineHeight = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sets = Calc.setCounts(gear, model.content)
            // always this tall, so a match arriving later doesn't push the list down
            Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (match != null) MatchChip(match.pct)
                sets.forEach { (s, _) -> SetIcon(s.icon, 16.dp) }
            }
        }
        TierDots(t, 6.5.dp)
        Text("$t", style = dela(14.sp, C.pink))
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.5.dp).background(C.line))
}

@Composable
private fun TierEditor(model: AppModel, h: OwnedHero, onClose: () -> Unit) {
    val t = model.tier(h.id)
    Column(Modifier.fillMaxWidth().hatch().border(2.5.dp, C.ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().tap(onClose), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Face(model.face(h), 36.dp)
            Text(model.heroName(h), style = zen(14.sp).copy(lineHeight = 17.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tierLabel(t), style = zen(12.sp, color = C.deepPink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TierBar(t) { model.setTier(h.id, it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(TIER_LABELS[0], style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
            LinkText("Hero details", size = 11) { model.go(Screen.HeroDetail(h.id)) }
            Text(TIER_LABELS[4], style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
fun MissingSetRow(st: e7.gearscan.data.SetDef) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(C.lightPink).border(2.dp, C.ink, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SetIcon(st.icon, 18.dp)
        Text("No ${st.name} set", style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("−${Math.round(e7.gearscan.data.Match.MISSING_SET_PER_PIECE * st.pieces * 100)}%", style = zen(12.sp, color = C.red))
    }
}

@Composable
private fun MatchCard(model: AppModel, id: Long, m: Match.Result) {
    Card(shadow = C.ink, radius = 14.dp, border = 2.5.dp, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Match with", style = zen(13.sp))
            LinkText(m.label, size = 13) { model.sheet = Sheet(SheetKind.PRESET, id) }
            Spacer(Modifier.weight(1f))
            Text("${m.pct}%", style = dela(20.sp).pinkShadow(4f))
        }
        VSpace(8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            m.missing.forEach { st -> MissingSetRow(st) }
            m.rows.forEach { r ->
                val shape = RoundedCornerShape(8.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(if (r.met) C.ghostBg else C.lightPink)
                    .border(2.dp, if (r.met) C.blue else C.ink, shape).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.stat.label, style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FitText(fmtInt(Math.floor(r.now)), zen(13.sp), Modifier.width(52.dp), textAlign = TextAlign.End)
                    FitText("/ ${fmtInt(r.target)}", zen(12.sp, color = C.muted), Modifier.width(58.dp))
                    val surplus = r.now - r.target
                    Row(Modifier.width(52.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (!r.met) FitText("−" + fmtInt(Math.ceil(r.target - r.now)), zen(12.sp, color = C.red))
                        else {
                            CheckMark(10.dp, C.blue)
                            if (surplus >= 0.05 * r.target) FitText("+" + fmtInt(Math.floor(surplus)), zen(12.sp, color = C.blue))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeroDetailScreen(model: AppModel, id: Long) {
    model.rev
    val h = model.hero(id) ?: return
    val cur = model.currentBuild(id)
    val gear = model.box?.gearOf(id) ?: emptyMap()
    val cfg = model.config(id)
    val tier = model.tier(id)
    Frame(model, Screen.Heroes, actions = {
        PinkButton("Optimize ${model.heroName(h)}", Modifier.weight(1f), enabled = model.gameHero(h) != null, short = "Optimize") {
            model.pickHero(id); model.team = listOf(id); model.tab(Screen.Optimize)
        }
        OutlineButton("Bonus stats") { model.sheet = Sheet(SheetKind.BONUS, id) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackLink("Heroes") { model.back() }
            Spacer(Modifier.weight(1f))
            Text("Tier $tier", style = zen(12.sp, color = C.muted))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Face(model.face(h), 70.dp)
            Column(Modifier.weight(1f)) {
                Text(model.heroName(h), style = dela(24.sp).pinkShadow(), maxLines = 2)
                val g = model.gameHero(h)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    g?.element?.let { AsyncImage(gameImage("icons/elements/element-$it.png"), null, Modifier.width(14.dp).height(14.dp)) }
                    val art = model.content.artifact(cfg.artifact ?: model.request(id).artifact)
                    ArtifactIcon(model, art, 18.dp)
                    Text(art?.name ?: "No artifact chosen",
                        style = zen(12.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Artifact +30 · full imprint, always", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        LaunchedEffect(id) { model.loadPresetsQuietly(id, network = true) }
        val match = model.matchFor(id)
        if (match != null) MatchCard(model, id, match)
        else if (cur != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(listOf(Stat.ATK, Stat.HP, Stat.SPD, Stat.DEF), listOf(Stat.CR, Stat.CD, Stat.EFF, Stat.RES)).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { s ->
                            Column(Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.paper)
                                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.short.uppercase(), style = zen(9.sp, color = C.muted))
                                FitText(fmtNum(Math.floor(cur.final[s])) + if (s.pct) "%" else "", dela(15.sp))
                            }
                        }
                    }
                }
            }
        } else Text("No game data for this hero yet.", style = zen(12.sp, color = C.red))
        Card(shadow = null, radius = 14.dp, border = 2.5.dp, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gear priority", style = zen(13.sp), modifier = Modifier.weight(1f))
                Text(tierLabel(tier), style = zen(12.sp, color = C.deepPink))
            }
            VSpace(8.dp)
            TierBar(tier) { model.setTier(id, it) }
            VSpace(4.dp)
            Text(if (tier == 1) "Takes only gear no one wears." else "Can take gear from tiers 1–${tier - 1}.",
                style = zen(11.sp, FontWeight.Medium, C.muted))
        }
        SectionTitle("Gear now")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Slot.entries.forEach { s -> GearTile(gear[s], 46.dp, slotIcon = s.icon, setIcon = gear[s]?.let { model.content.set(it.set)?.icon }) }
        }
        SectionTitle("Saved builds")
        if (cfg.saved.isEmpty()) Text("No saved builds yet. Save one from a search result.", style = zen(12.sp, FontWeight.Medium, C.muted))
        val setup = model.setup(id)
        cfg.saved.forEachIndexed { i, sb ->
            val items = model.savedItems(sb)
            val equipped = items.isNotEmpty() && items.all { it.equippedOn == id } && items.size == gear.size
            val b = setup?.build(items)
            Card(shadow = if (equipped) C.pink else null, radius = 14.dp, border = 2.5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(sb.name, style = dela(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (equipped) Stamp("装", "Equipped")
                        }
                        if (b != null) Text("Spd ${fmtNum(Math.floor(b.final[Stat.SPD]))} · Cr ${fmtNum(Math.floor(b.final[Stat.CR]))} · Cd ${fmtNum(Math.floor(b.final[Stat.CD]))}" +
                            if (items.size < sb.items.size) " · ${sb.items.size - items.size} pieces gone" else "",
                            style = zen(11.sp, FontWeight.Medium, C.muted))
                    }
                    Calc.setCounts(items, model.content).forEach { (s, _) -> SetIcon(s.icon, 22.dp) }
                    if (!equipped && b != null) Pill("Equip", onClick = { model.equip(id, b) })
                    Text("×", style = zen(16.sp, color = C.muted), modifier = Modifier.tap { cfg.saved.removeAt(i); model.touch(); model.save() }.padding(4.dp))
                }
            }
        }
    }
}
