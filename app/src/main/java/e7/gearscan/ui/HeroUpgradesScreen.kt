package e7.gearscan.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import e7.gearscan.data.Stat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    model.graph.tracker.records[id]?.takeIf { it.scan == model.lastScan }?.item ?: model.box?.itemsById?.get(id)

/** Improve heroes: pieces below +15 that could make a hero with a setup better once finished, by hero or by piece. */
@Composable
fun HeroUpgradesScreen(model: AppModel, heroId: Long?) {
    model.box ?: return
    val st = model.heroUpgrades
    val result = st?.result
    val running = st?.running == true
    val minGain = model.heroUpgradeMinGain
    val view = remember(result, minGain, model.heroUpgradeRebuilds, model.rev, model.box, model.clearedIds) { result?.let { model.heroUpgradeView(it, minGain) } }
    val one = heroId?.let { id -> view?.firstOrNull { it.hero.id == id } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listed = when {
        one != null -> one.rows.map { it.pick.piece }
        tab == 1 -> view?.let { piecesOf(model, it).map { p -> p.first } }.orEmpty()
        else -> view?.flatMap { h -> h.rows.map { it.pick.piece } }?.distinct().orEmpty()
    }.filter { it !in model.clearedIds }
    Frame(null, null, actions = {
        when {
            running -> OutlineButton("Stop", Modifier.weight(1f)) { model.stopHeroUpgrades() }
            result == null -> PinkButton(if (model.heroUpgradesResumable) "Continue the check" else "Check my gear", Modifier.weight(1f),
                enabled = model.pool?.running != true) { model.startHeroUpgrades() }
            else -> {
                OutlineButton(if (model.heroUpgradesResumable) "Continue" else "Check again", Modifier.weight(1f)) { model.startHeroUpgrades() }
                PinkButton("Track all ${listed.size}", Modifier.weight(1f), enabled = listed.isNotEmpty(), short = "Track ${listed.size}") { model.graph.messages.show(model.graph.tracker.trackPieces(listed)) }
            }
        }
    }) {
        BackToPrevious()
        // a hero's long name leaves no room for the kana beside it
        if (heroId == null) ScreenTitle("Improve heroes", "キョウカ")
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
        if (model.heroUpgradesStale) Notice("Your gear or setups changed since this check. Pieces that are gone dropped out; check again for the current answer.")
        if (one != null || heroId != null) {
            if (one == null) Text("This hero has no saved setup in this check.", style = zen(12.sp, FontWeight.Medium, C.muted))
            else HeroPage(model, one)
        } else {
            RowCard("Showing", { model.sheet = Sheet(SheetKind.HERO_UPGRADE_SETTINGS, 0) }, labelWidth = 84.dp) {
                Text("${GAINS.firstOrNull { it.first == minGain }?.second ?: "Any"} or more · ${if (model.heroUpgradeRebuilds) "rebuilds allowed" else "this piece only"}",
                    style = zen(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Segmented(listOf("By hero", "By piece"), tab) { tab = it }
            // hero rows first, then every listed piece: the chips are ready before most rows are seen
            LaunchedEffect(view, model.heroUpgradeRebuilds) {
                val v = view ?: return@LaunchedEffect
                model.warmHeroUpgradeChanges(v.mapNotNull { h -> h.rows.firstOrNull()?.let { h.hero.id to it } } +
                    v.flatMap { h -> h.rows.map { h.hero.id to it } })
            }
            Summary(model, view!!, st, result)
            if (tab == 0) ByHero(model, view) else ByPiece(model, view)
        }
        ListRow({ model.sheet = Sheet(SheetKind.HERO_UPGRADE_HELP, 0) }, Modifier.padding(vertical = 6.dp)) {
            Text("How the check works", style = zen(13.sp, color = C.muted))
        }
    }
}

@Composable
private fun Intro(model: AppModel) {
    val heroes = model.heroUpgradeHeroes()
    Card(padding = PaddingValues(12.dp)) {
        Text("Which unfinished pieces would make your heroes better?", style = zen(15.sp, FontWeight.Bold))
        VSpace(8.dp)
        Text("Checks the ${heroes.size} ${if (heroes.size == 1) "hero" else "heroes"} with a saved setup:", style = zen(12.sp, FontWeight.Medium, C.muted))
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            heroes.take(7).forEach { Face(model.face(it), 30.dp, ringWidth = 2.dp) }
            if (heroes.size > 7) Text("+${heroes.size - 7}", style = zen(12.sp, FontWeight.Medium, C.muted))
        }
    }
    if (model.heroUpgradesResumable) Text("The last check stopped before the end; it picks up where it was.", style = zen(11.sp, FontWeight.Medium, C.muted))
    if (model.pool?.running == true) Text("Starts after the other gear check.", style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
private fun Notice(text: String) {
    Text(text, style = zen(11.sp, FontWeight.Medium, C.deepPink))
}

/** Smallest gain and whether other pieces may move: what the lists count, edited in one place. */
@Composable
internal fun HeroUpgradeSettingsSheet(model: AppModel) {
    SheetBody("Showing", button = "Done", onButton = { model.sheet = null }) {
        SectionTitle("Smallest gain")
        Segmented(GAINS.map { it.second }, GAINS.indexOfFirst { it.first == model.heroUpgradeMinGain }.coerceAtLeast(0)) { model.useHeroUpgradeMinGain(GAINS[it].first) }
        Text("One roll is an average substat roll toward the hero's targets, such as 4 speed.", style = zen(11.sp, FontWeight.Medium, C.muted))
        SectionTitle("Builds")
        Segmented(listOf("This piece only", "Allow rebuilds"), if (model.heroUpgradeRebuilds) 1 else 0) { model.useHeroUpgradeRebuilds(it == 1) }
        Text(if (model.heroUpgradeRebuilds) "The best build with the finished piece, even if other pieces or sets change."
            else "The finished piece goes into the hero's slot; everything else the hero wears stays.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

private fun reason(h: AppModel.HeroUpgradeHero) = when {
    h.base.unresolved -> "Its starting build took too long to find"
    h.base.value == null -> "No build meets its setup, and no single finish makes one"
    h.base.worn == null -> "What it wears doesn't meet its setup"
    h.bestNow == 100 -> "Already meets every target"
    else -> "No finish gains this much"
}

/** Seen in this few of the finishes: a lucky outcome, folded away from the likely ones. */
private const val LONG_SHOT = 2

/** "~60%" over "18 of 30 tries": the chance is an estimate from a fixed number of simulated finishes. */
@Composable
private fun Odds(r: AppModel.HeroUpgradeRow, big: Boolean = true) {
    Column(horizontalAlignment = Alignment.End) {
        FitText("~${r.chance}%", dela(if (big) 18.sp else 13.sp, C.blue))
        FitText("${r.hits} of ${r.sims} tries", zen(9.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun Summary(model: AppModel, view: List<AppModel.HeroUpgradeHero>, st: e7.gearscan.HeroUpgradeState, r: HeroUpgrades.Result) {
    val better = view.filter { it.rows.isNotEmpty() }
    Card(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${better.size}", style = dela(32.sp).pinkShadow())
            Text(if (better.size == 1) "hero can get better" else "heroes can get better", style = zen(13.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("${better.flatMap { h -> h.rows.map { it.pick.piece } }.distinct().size} pieces · checked ${model.graph.scan.scannedLabelOf(st.finishedAt)}",
            style = zen(11.sp, FontWeight.Medium, C.muted))
        if (r.unknownSteps > 0 && !st.running) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${r.unknownSteps} ${if (r.unknownSteps == 1) "check" else "checks"} not finished", style = zen(11.sp, FontWeight.Medium, C.deepPink), modifier = Modifier.weight(1f))
            Pill("Finish them", onClick = { model.startHeroUpgrades(more = true) })
        }
    }
}

@Composable
private fun ByHero(model: AppModel, view: List<AppModel.HeroUpgradeHero>) {
    val better = view.filter { it.rows.isNotEmpty() }
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
    // the piece with the most to gain (chance x size, the hero page's order): its chance on the right, what it gives below
    val top = h.rows.firstOrNull()
    ListRow({ model.go(Screen.HeroUpgrades(h.hero.id)) }, leading = { Face(model.face(h.hero), 40.dp) }, trailing = {
        Slot(64.dp) { if (top != null && h.base.value != null) Column(horizontalAlignment = Alignment.End) {
            FitText("~${top.chance}%", dela(16.sp, C.blue))
            Text("best chance", style = zen(9.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
    }) {
        Text(model.heroName(h.hero), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        when {
            top == null -> Text(reason(h), style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.base.value == null -> Text("${h.rows.size} ${if (h.rows.size == 1) "piece" else "pieces"} could make a build",
                style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> StatChanges(model, h, top, short = true)
        }
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
    val all = remember(view) { piecesOf(model, view) }
    val (likely, long) = all.partition { (_, hs) -> hs.maxOf { it.second.hits } > LONG_SHOT }
    var count by rememberSavedFor(view, 20)
    var showLong by rememberSavedFor(view, false)
    val shown = likely + if (showLong) long else emptyList()
    shown.take(count).forEach { (id, heroes) ->
        val item = heroUpgradeItem(model, id) ?: return@forEach
        val r0 = heroes.first().second
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, id) }) {
            GearSearchIdentity(model, item, owner = true) { Odds(r0) }
            VSpace(8.dp)
            heroes.take(3).forEach { (h, r) -> HeroLine(model, h, r) }
            if (heroes.size > 3) Text("and ${heroes.size - 3} more heroes", style = zen(10.sp, FontWeight.Medium, C.muted))
        }
    }
    if (count < shown.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
    if (long.isNotEmpty()) LongShots(long.size, showLong) { showLong = !showLong }
}

@Composable
private fun LongShots(n: Int, open: Boolean, toggle: () -> Unit) {
    ListRow(toggle, Modifier.padding(vertical = 4.dp), trailing = { Slot(48.dp) { Text("$n", style = zen(12.sp, color = C.muted)) } }) {
        Text(if (open) "Hide long shots" else "Long shots", style = zen(13.sp, FontWeight.Bold))
        Text("seen in only 1 or 2 of the tries", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun HeroLine(model: AppModel, h: AppModel.HeroUpgradeHero, r: AppModel.HeroUpgradeRow) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Face(model.face(h.hero), 24.dp, ringWidth = 1.5.dp)
        Text(model.heroName(h.hero), style = zen(12.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        FitText("~${r.chance}%", zen(12.sp, color = C.blue))
    }
    StatChanges(model, h, r)
}

/** The hero's stat changes from a usual finish (or the best one, with rebuilds), marked as an estimate; space kept while they load. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StatChanges(model: AppModel, h: AppModel.HeroUpgradeHero, r: AppModel.HeroUpgradeRow, short: Boolean = false) {
    val key = Triple(h.hero.id, r.pick.piece, model.heroUpgradeRebuilds)
    LaunchedEffect(key) { model.warmHeroUpgradeChanges(listOf(h.hero.id to r)) }
    val list = model.heroUpgradeChanges[key]
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (list == null) return@FlowRow
        val label = if (model.heroUpgradeRebuilds) (if (short) "best ~" else "best finish ~") else (if (short) "usually ~" else "usual finish ~")
        Text(label, style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.align(Alignment.CenterVertically))
        if (list.isEmpty()) Text("no stat change", style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.align(Alignment.CenterVertically))
        list.take(4).forEach { (stat, d) -> StatChangeChip(stat, d) }
    }
}

@Composable
private fun StatChangeChip(stat: Stat, d: Double) {
    val up = d > 0
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(if (up) C.ghostBg else C.lightPink).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StatIcon(stat, 11.dp, if (up) C.blue else C.red)
        FitText((if (up) "+" else "−") + fmtNum(kotlin.math.abs(d)), zen(11.sp, FontWeight.Bold, if (up) C.blue else C.red))
    }
}

@Composable
private fun HeroPage(model: AppModel, h: AppModel.HeroUpgradeHero) {
    val rebuilds = model.heroUpgradeRebuilds
    Card(padding = PaddingValues(12.dp), onClick = { model.go(Screen.HeroDetail(h.hero.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Face(model.face(h.hero), 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.heroName(h.hero), style = dela(17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when {
                    rebuilds && h.base.value == null && !h.base.unresolved -> "No build meets the setup yet"
                    !rebuilds && h.base.worn == null -> "What it wears doesn't meet its setup"
                    h.bestNow != null -> if (rebuilds) "Best build now · ${h.bestNow}% match" else "Wearing now · ${h.bestNow}% match"
                    else -> if (rebuilds) "Gains are over its best build now" else "Gains are over what it wears"
                }, style = zen(12.sp, FontWeight.Medium, C.muted))
                h.bestNow?.let { Bar(it / 100f) }
            }
            ChevronSlot(true)
        }
    }
    // what it wears is behind its best build: optimizing comes before any upgrade
    val best = h.base.value; val worn = h.base.worn
    if (!rebuilds && best != null && worn != null && best > worn + HeroUpgrades.MIN_GAIN * h.base.unit)
        Card(shadow = null, padding = PaddingValues(12.dp), onClick = { model.pickHero(h.hero.id); model.team = listOf(h.hero.id); model.tab(Screen.Optimize) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Optimize first: a better build from gear you have is " +
                        model.heroGainLabel(h.hero.id, HeroUpgrades.Base(h.hero.id, worn, emptyList(), false, h.base.unit, 0), best) + " ahead.", style = zen(12.sp))
                }
                ChevronSlot(true)
            }
        }
    if (h.rows.isEmpty()) {
        Text(reason(h), style = zen(12.sp, FontWeight.Medium, C.muted))
        return
    }
    val rows = h.rows.filter { it.pick.piece !in model.clearedIds }
    val (likely, long) = rows.partition { it.hits > LONG_SHOT }
    var count by rememberSavedFor(h, 20)
    var showLong by rememberSavedFor(h, false)
    SectionTitle("${likely.size} ${if (likely.size == 1) "piece" else "pieces"} could help")
    val shown = likely + if (showLong) long else emptyList()
    shown.take(count).forEach { r ->
        val item = heroUpgradeItem(model, r.pick.piece) ?: return@forEach
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = PaddingValues(12.dp), onClick = { model.sheet = Sheet(SheetKind.HERO_UPGRADE_PIECE, r.pick.piece) }) {
            GearSearchIdentity(model, item, owner = true) { Odds(r) }
            if (h.base.value == null) Text("Makes a build possible", style = zen(12.sp, color = C.blue)) else StatChanges(model, h, r)
            RebuildLine(model, r)
        }
    }
    if (count < shown.size) OutlineButton("Show 20 more", Modifier.fillMaxWidth(), height = 42.dp) { count += 20 }
    if (long.isNotEmpty()) LongShots(long.size, showLong) { showLong = !showLong }
    if (h.open > 0) Text("${h.open} pieces still have finishes the check couldn't settle in time.", style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
internal fun HeroUpgradePieceSheet(model: AppModel, id: Long) {
    val result = model.heroUpgrades?.result ?: return
    val item = heroUpgradeItem(model, id) ?: return
    val heroes = remember(result, model.heroUpgradeMinGain, model.rev) {
        model.heroUpgradeView(result).mapNotNull { h -> h.rows.firstOrNull { it.pick.piece == id }?.let { h to it } }
    }
    SheetBody("Worth enhancing?", button = "Enhance over the game", onButton = { model.sheet = null; model.graph.messages.show(model.graph.tracker.trackPiece(id)) }) {
        Card(shadow = null, padding = PaddingValues(12.dp)) { GearSearchIdentity(model, item, owner = true) }
        heroes.forEach { (h, r) ->
            Card(shadow = null, padding = PaddingValues(12.dp), onClick = { model.sheet = null; model.go(Screen.HeroUpgrades(h.hero.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Face(model.face(h.hero), 36.dp)
                    Text(model.heroName(h.hero), style = zen(13.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Odds(r)
                    ChevronSlot(true)
                }
                if (h.base.value == null) Text("Makes a build possible", style = zen(12.sp, color = C.blue)) else StatChanges(model, h, r)
                RebuildLine(model, r)
                ListRow({ model.openFinalBuild(h.hero.id, r); model.sheet = Sheet(SheetKind.HERO_UPGRADE_BUILD, id, back = SheetKind.HERO_UPGRADE_PIECE) },
                    Modifier.padding(top = 6.dp)) {
                    Text("Final build with this piece", style = zen(12.sp))
                }
            }
        }
        val share = e7.gearscan.opt.EsForecast.finishShare(item)
        if (share > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cost to +15", style = zen(12.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
            Text("~${e7.gearscan.opt.EsForecast.goldText(share * e7.gearscan.opt.EsForecast.FULL_GOLD)} gold", style = zen(13.sp, FontWeight.Bold))
        }
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
        Card(shadow = null, padding = PaddingValues(12.dp)) {
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
        // only the slots that change: a column of "Stays" hid the one that matters
        val changed = fb.items.indices.filter { fb.from[it] != "Stays" }
        changed.forEach { i ->
            val it = fb.items[i]
            val mine = it.id == fb.pieceId
            Card(shadow = if (mine) C.pink else null, radius = 12.dp, border = 2.dp, padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GearTile(it, 40.dp, setIcon = model.content.set(it.set)?.icon)
                    Column(Modifier.weight(1f)) {
                        Text("${model.content.set(it.set)?.name ?: it.set.removeSuffix("Set")} ${it.slot.label}", style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fb.from[i], style = zen(11.sp, color = if (mine) C.deepPink else C.blue))
                        if (mine) GearSearchSubs(it)
                    }
                }
            }
        }
        val stay = fb.items.size - changed.size
        Text((if (stay > 0) "$stay other ${if (stay == 1) "piece stays" else "pieces stay"} · " else "") +
            (if (fb.sets.first == fb.sets.second) "sets stay: ${fb.sets.second}" else "sets: ${fb.sets.first} → ${fb.sets.second}"),
            style = zen(11.sp, FontWeight.Medium, C.muted))
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

@Composable
private fun Bar(f: Float) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(C.line)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(8.dp).clip(shape).background(C.pink))
    }
}
