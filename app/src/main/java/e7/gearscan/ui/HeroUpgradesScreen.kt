package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Screen
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.Item
import e7.gearscan.data.fmtNum
import e7.gearscan.opt.HeroUpgrades

/** The smallest-gain choices, in rolls; the first is the check's own floor. */
private val GAINS = listOf(HeroUpgrades.MIN_GAIN to "Any", 0.5 to "½ roll", 1.0 to "1 roll", 2.0 to "2 rolls")

/** The piece as the check saw it: a recorded upgrade of this scan wins over the scanned piece. */
internal fun heroUpgradeItem(model: AppModel, id: Long): Item? =
    model.upgrades.records[id]?.takeIf { it.scan == model.lastScan }?.item ?: model.box?.itemsById?.get(id)

/** Improve heroes: pieces below +15 that could make a hero with a setup better once finished, by hero or by piece. */
@Composable
fun HeroUpgradesScreen(model: AppModel, heroId: Long?) {
    model.box ?: return
    val st = model.heroUpgrades
    val result = st?.result
    val running = st?.running == true
    val minGain = model.heroUpgradeMinGain
    val view = remember(result, minGain, model.heroUpgradeRebuilds, model.rev, model.box, model.cleanupDone) { result?.let { model.heroUpgradeView(it, minGain) } }
    val one = heroId?.let { id -> view?.firstOrNull { it.hero.id == id } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listed = when {
        one != null -> one.rows.map { it.pick.piece }
        tab == 1 -> view?.let { piecesOf(model, it).map { p -> p.first } }.orEmpty()
        else -> view?.flatMap { h -> h.rows.map { it.pick.piece } }?.distinct().orEmpty()
    }.filter { it !in model.cleanupDone }
    Frame(null, null, actions = {
        when {
            running -> OutlineButton("Stop", Modifier.weight(1f)) { model.stopHeroUpgrades() }
            result == null -> PinkButton(if (model.heroUpgradesResumable) "Continue the check" else "Check my gear", Modifier.weight(1f),
                enabled = model.pool?.running != true && model.cleanup?.running != true) { model.startHeroUpgrades() }
            else -> {
                OutlineButton(if (model.heroUpgradesResumable) "Continue" else "Check again", Modifier.weight(1f)) { model.startHeroUpgrades() }
                PinkButton("Track all ${listed.size}", Modifier.weight(1f), enabled = listed.isNotEmpty(), short = "Track ${listed.size}") { model.trackHeroUpgrades(listed) }
            }
        }
    }) {
        BackToPrevious(model)
        // a hero's long name leaves no room for the kana beside it
        if (heroId == null) ScreenTitle("Improve heroes", "キョウカ") else ScreenTitle(model.hero(heroId)?.let(model::heroName) ?: "Improve heroes")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkText("How it works") { model.sheet = Sheet(SheetKind.HERO_UPGRADE_HELP, 0) }
            LinkText("Inventory order") { model.sheet = Sheet(SheetKind.INVENTORY_ORDER, 0) }
        }
        if (running) Card(padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spinner()
                Text("Checking ${model.heroUpgradeHeroes().size} heroes · ${if (st!!.total > 0) st.done * 100 / st.total else 0}%", style = zen(13.sp))
            }
            VSpace(8.dp)
            Bar(if (st!!.total > 0) st.done.toFloat() / st.total else 0f)
            VSpace(8.dp)
            Text("Keeps going in the game. You'll get a notification when it's done.", style = zen(11.sp, FontWeight.Medium, C.muted))
        }
        if (result == null) {
            if (!running) Intro(model)
            return@Frame
        }
        Notices(model, result)
        SectionTitle("Smallest gain")
        Segmented(GAINS.map { it.second }, GAINS.indexOfFirst { it.first == minGain }.coerceAtLeast(0)) { model.useHeroUpgradeMinGain(GAINS[it].first) }
        SectionTitle("Builds")
        Segmented(listOf("This piece only", "Allow rebuilds"), if (model.heroUpgradeRebuilds) 1 else 0) { model.useHeroUpgradeRebuilds(it == 1) }
        Text(if (model.heroUpgradeRebuilds) "The best build with the finished piece, even if other pieces or sets change."
            else "The finished piece goes into the hero's slot; everything else the hero wears stays.", style = zen(11.sp, FontWeight.Medium, C.muted))
        if (one != null || heroId != null) {
            if (one == null) Text("This hero has no saved setup in this check.", style = zen(12.sp, FontWeight.Medium, C.muted))
            else HeroPage(model, one)
        } else {
            Segmented(listOf("By hero", "By piece"), tab) { tab = it }
            if (tab == 0) ByHero(model, view!!, st) else ByPiece(model, view!!)
        }
    }
}

@Composable
private fun Intro(model: AppModel) {
    Card(padding = PaddingValues(12.dp)) {
        Text("Finds pieces below +15 that could make your heroes better once you enhance them.", style = zen(13.sp))
        VSpace(8.dp)
        Text("· Heroes: the ${model.heroUpgradeHeroes().size} with a saved setup. Each one's bar is the best build it can make now " +
            "from its own gear and spares, with every piece at its current level.", style = zen(12.sp, FontWeight.Medium))
        VSpace(4.dp)
        Text("· Every piece gets ${HeroUpgrades.SIMS} simulated finishes. A finish counts when the hero's best complete build with it " +
            "beats that bar, with the setup's sets, mains and limits.", style = zen(12.sp, FontWeight.Medium))
        VSpace(8.dp)
        Text("Gear on heroes of your pool run stays with them. Takes a minute or two; it keeps going if you leave the app.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    }
    if (model.heroUpgradesResumable) Text("The last check stopped before the end; it picks up where it was.", style = zen(11.sp, FontWeight.Medium, C.muted))
    if (model.pool?.running == true || model.cleanup?.running == true) Text("Starts after the other gear check.", style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
private fun Notices(model: AppModel, r: HeroUpgrades.Result) {
    if (model.heroUpgradesStale) Notice("Your gear or setups changed since this check. Pieces that are gone dropped out; check again for the current answer.")
    if (r.unknownSteps > 0 && model.heroUpgrades?.running != true) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.lightPink).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${r.unknownSteps} searches ran out of time; some finishes are still open.", style = zen(12.sp), modifier = Modifier.weight(1f))
        LinkText("Check the rest") { model.startHeroUpgrades(more = true) }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = zen(11.sp, FontWeight.Medium, C.deepPink))
}

private fun reason(h: AppModel.HeroUpgradeHero) = when {
    h.base.unresolved -> "Its starting build took too long to find"
    h.base.value == null -> "No build meets its setup, and no single finish makes one"
    h.base.worn == null -> "What it wears doesn't meet its setup"
    h.bestNow == 100 -> "Already meets every target"
    else -> "No finish gains this much"
}

@Composable
private fun ByHero(model: AppModel, view: List<AppModel.HeroUpgradeHero>, st: e7.gearscan.HeroUpgradeState) {
    val better = view.filter { it.rows.isNotEmpty() }
    Card(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${better.size}", style = dela(32.sp).pinkShadow())
            Text(if (better.size == 1) "hero can get better" else "heroes can get better", style = zen(13.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("${better.flatMap { h -> h.rows.map { it.pick.piece } }.distinct().size} pieces · of ${view.size} heroes with a setup · checked ${model.scannedLabelOf(st.finishedAt)}",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    }
    if (better.isNotEmpty()) SectionTitle("Can get better", trailing = "${better.size}")
    better.forEach { h -> HeroRow(model, h) }
    val rest = view.filter { it.rows.isEmpty() }
    if (rest.isNotEmpty()) {
        SectionTitle("Nothing to gain now", trailing = "${rest.size}")
        rest.forEach { h -> HeroRow(model, h) }
    }
}

@Composable
private fun HeroRow(model: AppModel, h: AppModel.HeroUpgradeHero) {
    ListRow({ model.go(Screen.HeroUpgrades(h.hero.id)) }, leading = { Face(model.face(h.hero), 40.dp) }, trailing = {
        Slot(44.dp) { h.bestNow?.let { MatchChip(it) } }
    }) {
        Text(model.heroName(h.hero), style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val top = h.rows.firstOrNull()
        val pieces = "${h.rows.size} ${if (h.rows.size == 1) "piece" else "pieces"}"
        Text(when {
            top == null -> reason(h)
            h.base.value == null -> "$pieces could make a build · top: ${top.chance}% chance"
            else -> "$pieces · top: ${top.chance}% chance of ${top.typical}"
        },
            style = zen(11.sp, FontWeight.Medium, if (top == null) C.muted else C.blue), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Every listed piece with the heroes it helps, the pieces for higher tiers and bigger expected gains first. */
private fun piecesOf(model: AppModel, view: List<AppModel.HeroUpgradeHero>): List<Pair<Long, List<Pair<AppModel.HeroUpgradeHero, AppModel.HeroUpgradeRow>>>> =
    view.flatMap { h -> h.rows.map { r -> r.pick.piece to (h to r) } }.groupBy({ it.first }, { it.second })
        .map { (id, hs) -> id to hs.sortedWith(compareByDescending<Pair<AppModel.HeroUpgradeHero, AppModel.HeroUpgradeRow>> { model.tier(it.first.hero.id) }.thenByDescending { it.second.expected }) }
        .sortedWith(compareByDescending<Pair<Long, List<Pair<AppModel.HeroUpgradeHero, AppModel.HeroUpgradeRow>>>> { model.tier(it.second.first().first.hero.id) }
            .thenByDescending { it.second.first().second.expected }.thenBy { it.first })

@Composable
private fun ByPiece(model: AppModel, view: List<AppModel.HeroUpgradeHero>) {
    val pieces = remember(view) { piecesOf(model, view) }
    var count by remember(view) { mutableIntStateOf(20) }
    Text("${pieces.size} pieces · for your highest tiers first", style = zen(11.sp, FontWeight.Medium, C.muted))
    pieces.take(count).forEach { (id, heroes) ->
        val item = heroUpgradeItem(model, id) ?: return@forEach
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, id) }) {
            GearSearchIdentity(model, item) {
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                    FitText("${heroes.first().second.chance}%", dela(15.sp, C.blue))
                    Text("best chance", style = zen(9.sp, FontWeight.Medium, C.muted))
                }
            }
            VSpace(8.dp)
            heroes.take(3).forEach { (h, r) -> HeroLine(model, h, r) }
            if (heroes.size > 3) Text("and ${heroes.size - 3} more heroes", style = zen(10.sp, FontWeight.Medium, C.muted))
            VSpace(4.dp)
            OwnerLine(model, item)
        }
    }
    if (count < pieces.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
}

@Composable
private fun HeroLine(model: AppModel, h: AppModel.HeroUpgradeHero, r: AppModel.HeroUpgradeRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Face(model.face(h.hero), 20.dp, ringWidth = 1.5.dp)
        Text(model.heroName(h.hero), style = zen(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        FitText("${r.chance}% · ${r.typical}", zen(11.sp, color = C.blue))
    }
}

@Composable
private fun OwnerLine(model: AppModel, item: Item) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(gearOwner(model, item), style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Finish to +15", style = zen(10.sp, color = C.blue))
        Spacer(Modifier.width(7.dp)); ChevronSlot(true)
    }
}

@Composable
private fun HeroPage(model: AppModel, h: AppModel.HeroUpgradeHero) {
    Card(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Face(model.face(h.hero), 52.dp)
            Column(Modifier.weight(1f)) {
                val rebuilds = model.heroUpgradeRebuilds
                Text(when {
                    rebuilds && h.base.value == null && !h.base.unresolved -> "No build meets the setup yet"
                    !rebuilds && h.base.worn == null -> "What it wears doesn't meet its setup"
                    h.bestNow != null -> if (rebuilds) "Best build now: ${h.bestNow}% match" else "Wearing now: ${h.bestNow}% match"
                    else -> if (rebuilds) "Best build now is the bar" else "What it wears is the bar"
                }, style = zen(13.sp))
                Text(if (rebuilds) "Gains are over the best build this hero can make now, from its own gear and spares."
                    else "Gains are over what this hero wears now, with only this piece changed.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        VSpace(6.dp)
        LinkText("Hero details") { model.go(Screen.HeroDetail(h.hero.id)) }
    }
    // what it wears is behind its best build: optimizing comes before any upgrade
    val best = h.base.value; val worn = h.base.worn
    if (!model.heroUpgradeRebuilds && best != null && worn != null && best > worn + HeroUpgrades.MIN_GAIN * h.base.unit) Card(shadow = null, padding = PaddingValues(12.dp)) {
        Text("Your best build now is ${model.heroGainLabel(h.hero.id, HeroUpgrades.Base(h.hero.id, worn, emptyList(), false, h.base.unit, 0), best)} better than what it wears.",
            style = zen(12.sp))
        Text("Optimize it first: that needs no upgrade.", style = zen(11.sp, FontWeight.Medium, C.muted))
        LinkText("Optimize ${model.heroName(h.hero)}") { model.pickHero(h.hero.id); model.team = listOf(h.hero.id); model.tab(Screen.Optimize) }
    }
    if (h.rows.isEmpty()) {
        Text(reason(h), style = zen(12.sp, FontWeight.Medium, C.muted))
        return
    }
    var count by remember(h) { mutableIntStateOf(20) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${h.rows.size} pieces", style = dela(16.sp), modifier = Modifier.weight(1f))
        Text("Most to gain first", style = zen(11.sp, color = C.muted))
    }
    h.rows.filter { it.pick.piece !in model.cleanupDone }.take(count).forEach { r ->
        val item = heroUpgradeItem(model, r.pick.piece) ?: return@forEach
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, r.pick.piece) }) {
            GearSearchIdentity(model, item) {
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                    FitText("${r.chance}%", dela(15.sp, C.blue))
                    Text("chance", style = zen(9.sp, FontWeight.Medium, C.muted))
                }
            }
            VSpace(8.dp)
            Text(if (h.base.value == null) "Makes a build possible" else "Usually ${r.typical} · up to ${r.best}", style = zen(12.sp, color = C.blue))
            RebuildLine(model, r)
            if (r.changes.isNotEmpty()) Text("Best finish: ${r.changes}", style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(4.dp)
            OwnerLine(model, item)
        }
    }
    if (count < h.rows.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
    if (h.open > 0) Text("${h.open} pieces still have finishes the check couldn't settle in time.", style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
internal fun HeroUpgradePieceSheet(model: AppModel, id: Long) {
    val result = model.heroUpgrades?.result ?: return
    val item = heroUpgradeItem(model, id) ?: return
    val heroes = remember(result, model.heroUpgradeMinGain, model.rev) {
        model.heroUpgradeView(result).mapNotNull { h -> h.rows.firstOrNull { it.pick.piece == id }?.let { h to it } }
    }
    SheetBody("Equipment", button = "Track over game", onButton = { model.sheet = null; model.trackHeroUpgrade(id) }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) { GearSearchIdentity(model, item) }
        model.inventoryPosition(item.id)?.let { pos ->
            Card(shadow = null, padding = PaddingValues(12.dp)) {
                Text(pos.label(), style = zen(13.sp, color = C.blue))
                Text(pos.grid() + " · 5 columns", style = zen(12.sp))
                Text(model.inventoryOrderLabel, style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        SectionTitle("Heroes it could improve", trailing = "${heroes.size}")
        heroes.forEach { (h, r) ->
            Card(shadow = null, padding = PaddingValues(12.dp), onClick = { model.sheet = null; model.go(Screen.HeroUpgrades(h.hero.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Face(model.face(h.hero), 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(model.heroName(h.hero), style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.hits} of ${r.sims} finishes · " + if (h.base.value == null) "makes a build possible" else "usually ${r.typical}, up to ${r.best}",
                            style = zen(11.sp, color = C.blue))
                    }
                    ChevronSlot(true)
                }
                RebuildLine(model, r)
                if (r.changes.isNotEmpty()) Text("Best finish: ${r.changes}", style = zen(10.sp, FontWeight.Medium, C.muted))
                LinkText("Final build") { model.openFinalBuild(h.hero.id, r); model.sheet = Sheet(SheetKind.HERO_UPGRADE_BUILD, id, back = SheetKind.HERO_UPGRADE_PIECE) }
            }
        }
        Text(gearOwner(model, item), style = zen(11.sp, FontWeight.Medium, C.muted))
        Text("Track over game records each +3 and rechecks whether to keep going.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

/** With rebuilds on, what else the best build changes: other pieces it moves, and its sets when they differ. */
@Composable
private fun RebuildLine(model: AppModel, r: AppModel.HeroUpgradeRow) {
    if (!model.heroUpgradeRebuilds) return
    val p = r.pick
    val text = when {
        p.moved == 0 && p.sets.isEmpty() -> "Best finish: only this piece changes"
        p.sets.isEmpty() -> "Best finish rebuilds: moves ${p.moved} other ${if (p.moved == 1) "piece" else "pieces"}"
        else -> "Best finish rebuilds: moves ${p.moved} other ${if (p.moved == 1) "piece" else "pieces"} · sets ${p.sets}"
    }
    Text(text, style = zen(10.sp, color = if (p.moved == 0 && p.sets.isEmpty()) C.muted else C.deepPink), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** The hero's final build with the piece's usual finish: every slot, where it comes from, sets and the stats its setup scores. */
@Composable
internal fun HeroUpgradeBuildSheet(model: AppModel) {
    val fb = model.finalBuild
    SheetBody("Final build", button = "Done", onButton = { model.sheet = null }) {
        if (fb == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.finalBuildBusy) { Spinner(); Text("Working out the build", style = zen(12.sp)) }
                else Text("No build meets this hero's setup with that finish.", style = zen(12.sp, FontWeight.Medium, C.muted))
            }
            return@SheetBody
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Face(model.face(fb.hero), 40.dp)
            Column(Modifier.weight(1f)) {
                Text(model.heroName(fb.hero), style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fb.note + " · usually ${fb.gain}", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        fb.items.forEachIndexed { i, it ->
            val mine = it.id == fb.pieceId
            Card(shadow = if (mine) C.pink else null, radius = 12.dp, border = 2.dp, padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GearTile(it, 40.dp, setIcon = model.content.set(it.set)?.icon)
                    Column(Modifier.weight(1f)) {
                        Text("${model.content.set(it.set)?.name ?: it.set.removeSuffix("Set")} ${it.slot.label} · ${it.mainType.short} ${it.mainType.format(it.mainValue)}",
                            style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fb.from[i], style = zen(11.sp, color = if (mine) C.deepPink else if (fb.from[i] == "Stays") C.muted else C.blue))
                        if (mine) GearSearchSubs(it)
                    }
                }
            }
        }
        Card(shadow = null, padding = PaddingValues(12.dp)) {
            Text(if (fb.sets.first == fb.sets.second) "Sets stay: ${fb.sets.second}" else "Sets: ${fb.sets.first} → ${fb.sets.second}", style = zen(12.sp))
            VSpace(6.dp)
            fb.stats.forEach { (stat, now, after) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatIcon(stat, 14.dp)
                    Text(stat.label, style = zen(12.sp), modifier = Modifier.weight(1f))
                    Text("${fmtNum(Math.floor(now))} → ${fmtNum(Math.floor(after))}", style = zen(12.sp, color = if (after > now + 0.5) C.blue else if (after < now - 0.5) C.deepPink else C.ink))
                }
            }
            VSpace(4.dp)
            Text("Now is what the hero wears in your scan.", style = zen(10.sp, FontWeight.Medium, C.muted))
        }
    }
}

@Composable
internal fun HeroUpgradeHelpSheet(model: AppModel) {
    SheetBody("How it works", button = "Got it", onButton = { model.sheet = null }) {
        SectionTitle("What is checked")
        Text("Every piece below +15 that is level 85 or 88 and Epic or Heroic, for every hero with a saved setup. Gear worn by heroes " +
            "of your pool run is only tried on its own hero.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("The bar")
        Text("Each hero's best build now, from its own gear and spares, with every piece at its current level. So a piece gets " +
            "credit only for what finishing it adds.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("The chance")
        Text("Each piece gets ${HeroUpgrades.SIMS} simulated finishes with the game's roll rules. The chance is the share of finishes " +
            "whose best complete build beats the bar by at least the smallest gain. Whole builds are searched, so a finish can " +
            "also change other slots or sets when Allow rebuilds is on; with This piece only, the finish just takes the hero's " +
            "piece in that slot and nothing else moves.", style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Gains")
        Text("Heroes with targets show match points: 1 point is 1% of the way from mains-only gear to every target met. Other " +
            "goals show their own stat. One roll is an average substat roll toward the hero's targets, such as 4 speed.",
            style = zen(12.sp, FontWeight.Medium))
        SectionTitle("Not covered")
        Text("Pieces that only help together with another upgrade, modification stones, Rare and older gear, and heroes without a " +
            "saved setup. Level 85 pieces count as reforged when the setup counts reforging, as in Optimize.", style = zen(12.sp, FontWeight.Medium))
    }
}
