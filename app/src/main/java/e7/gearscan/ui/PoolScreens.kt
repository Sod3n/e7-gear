package e7.gearscan.ui

import e7.gearscan.POOL_RULES
import e7.gearscan.data.Request
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.PoolState
import e7.gearscan.Screen
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Role
import e7.gearscan.data.fmtInt
import e7.gearscan.overlay.OverlayService
import e7.gearscan.overlay.OverlayState
import kotlinx.coroutines.delay

/** 4a: the Optimize screen's "Whole pool" mode. */
@Composable
fun PoolSetup(model: AppModel) {
    // drawn inside Frame's content, which skips unless it reads the change counter itself
    model.rev
    val heroes = model.poolHeroes()
    LaunchedEffect(heroes.map { it.id }) { heroes.forEach { model.loadPresetsQuietly(it.id, network = true) } }
    val now = heroes.associate { it.id to model.matchFor(it.id)?.pct }
    StaleBox(model)
    PendingCard(model)
    PoolScoreCard("POOL SCORE · ${heroes.size} HEROES", null, model.poolScore(now), note = model.lastPool?.let { (at, _) -> "last run ${ago(at)}" }) {}
    RowCard("Rules", { model.sheet = Sheet(SheetKind.ADVANCED, POOL_RULES) }) {
        val reqs = heroes.map { model.request(it.id) }
        fun <T> same(f: (Request) -> T) = reqs.map(f).distinct().singleOrNull()
        Text(listOf(
            same { it.minEnhance }?.let { "+$it gear" } ?: "mixed levels",
            when (same { it.mods }) { true -> "mods on"; false -> "mods off"; null -> "mods for some" },
            when (same { it.capCrit }) { true -> "crit cap 105"; false -> "no crit cap"; null -> "crit cap for some" },
        ).joinToString(" · "), style = zen(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    SectionTitle("Which heroes", trailing = "by tier")
    Card(padding = PaddingValues(0.dp)) {
        val all = model.box?.heroes.orEmpty().filter { model.gameHero(it) != null }
        for (t in 5 downTo 1) {
            val inTier = all.filter { model.tier(it.id) == t }
            if (inTier.isEmpty() && t < 5) continue
            val on = t in model.poolTiers
            Row(Modifier.fillMaxWidth().tap { model.poolTiers = if (on) model.poolTiers - t else model.poolTiers + t; model.save() }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(on) { model.poolTiers = if (it) model.poolTiers + t else model.poolTiers - t; model.save() }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$t", style = dela(13.sp, C.pink)); Text(e7.gearscan.ui.TIER_LABELS[t - 1], style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val avg = inTier.mapNotNull { model.matchFor(it.id)?.pct }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                    Text("${inTier.size} heroes" + (avg?.let { " · now $it%" } ?: ""), style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FaceStack(model, inTier)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.5.dp).background(C.line))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (model.poolExtra.isEmpty()) "Or pick heroes one by one" else "${model.poolExtra.size} picked one by one",
                style = zen(12.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
            LinkText("Choose") { model.sheet = Sheet(SheetKind.POOL_PICK, 0) }
        }
    }
    val pending = heroes.count { model.hasBuild(it.id) == null }
    val noBuild = heroes.filter { model.hasBuild(it.id) == false }
    val ready = heroes.count { model.hasBuild(it.id) == true }
    Card(padding = PaddingValues(0.dp)) {
        val reviewed = heroes.count { model.hasBuild(it.id) == true && model.request(it.id).reviewed }
        ListRow(if (ready > 0) ({ model.sheet = Sheet(SheetKind.POOL_LIST, 0) }) else null, Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(C.blue)) }) {
            Text(if (pending > 0) "$ready ready · loading builds for $pending" else "$ready use their preset · $reviewed reviewed",
                style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (noBuild.isNotEmpty()) ListRow({ model.go(Screen.PoolHero(noBuild.first().id, setup = true)) },
            Modifier.hatch().padding(horizontal = 12.dp, vertical = 11.dp), leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(C.pink)) },
            trailing = { Text("Set up", style = zen(12.sp, color = C.deepPink)) }) {
            Text("${noBuild.size} have no build yet", style = zen(13.sp))
        }
    }
}

/** The last run, until it's equipped or another run replaces it. */
@Composable
private fun PendingCard(model: AppModel) {
    val at = model.pendingPool ?: return
    val e = model.history.firstOrNull { it.at == at } ?: return
    Card(shadow = C.pink, padding = PaddingValues(0.dp)) {
        ListRow({ model.openPending() }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Pending run · ${historyStamp(at)}", style = zen(13.sp))
            Text("${fmtInt(e.before.toDouble())} → ${fmtInt(e.after.toDouble())} · ${e.builds.size} to equip · ${e.reviewed.size} reviewed. Stays until you equip it or run again.",
                style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 4a's button: hero count plus a time estimate. */
@Composable
fun RowScope.PoolButton(model: AppModel) {
    model.rev
    val heroes = model.poolHeroes()
    val ready = heroes.count { model.hasBuild(it.id) == true }
    val skipped = heroes.count { model.hasBuild(it.id) == false }
    val seconds = ready * model.poolSecondsPerHero
    val time = if (seconds < 60) "about ${maxOf(5, Math.round(seconds / 5).toInt() * 5)} s" else "about ${Math.round(seconds / 60)} min"
    val shape = RoundedCornerShape(999.dp)
    Column(Modifier.weight(1f).height(56.dp).then(if (ready > 0) Modifier.hardShadow(C.ink, 3.dp, 4.dp, 28.dp) else Modifier).clip(shape)
        .background(if (ready > 0) C.pink else C.line).border(2.5.dp, C.ink, shape)
        .tap(if (ready > 0) ({ model.startPool(); model.go(Screen.PoolRun) }) else null),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        FitText("Optimize $ready heroes", dela(16.sp))
        FitText(time + if (skipped > 0) " · $skipped skipped unless set up" else "", zen(10.sp, FontWeight.Medium))
    }
}

@Composable
private fun FaceStack(model: AppModel, heroes: List<OwnedHero>) {
    Box(Modifier.width(62.dp).height(30.dp)) {
        heroes.take(2).forEachIndexed { i, h -> Box(Modifier.offset((i * 16).dp, 0.dp)) { Face(model.face(h), 30.dp, ringWidth = 2.dp) } }
        if (heroes.size > 2) Box(Modifier.offset(32.dp, 0.dp).size(30.dp).clip(CircleShape).background(C.ink).border(2.dp, C.card, CircleShape),
            contentAlignment = Alignment.Center) { FitText(if (heroes.size - 2 > 99) "99+" else "+${heroes.size - 2}", dela(10.sp, C.card), Modifier.padding(horizontal = 3.dp)) }
    }
}

@Composable
private fun PoolScoreCard(label: String, before: Int?, score: Int, note: String? = null, trailing: @Composable () -> Unit) {
    Card(shadow = C.pink, radius = 16.dp, border = 2.5.dp, halftone = true, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = zen(10.sp, color = C.muted).copy(letterSpacing = 0.6.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (before != null) { Text(fmtInt(before.toDouble()), style = zen(13.sp, color = C.muted)); Text("→", style = zen(13.sp, color = C.muted)) }
            // sized for "10,000" so a changing score never pushes its neighbours
            Box(Modifier.width(118.dp)) { FitText(fmtInt(score.toDouble()), dela(30.sp).pinkShadow(5f)) }
            if (before == null) Text("of 10,000", style = zen(11.sp, FontWeight.Medium, C.muted), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            trailing()
        }
        VSpace(6.dp)
        ScoreBar(score / 10_000f, before?.let { it / 10_000f })
    }
}

/** Ink up to [before], pink for the gain on top. */
@Composable
private fun ScoreBar(now: Float, before: Float?) {
    val shape = RoundedCornerShape(99.dp)
    Box(Modifier.fillMaxWidth().height(9.dp).clip(shape).background(C.card).border(2.dp, C.ink, shape)) {
        Box(Modifier.fillMaxWidth(now.coerceIn(0f, 1f)).height(9.dp).background(C.pink))
        Box(Modifier.fillMaxWidth((before ?: now).coerceIn(0f, 1f)).height(9.dp).background(C.ink))
    }
}

private fun ago(at: Long): String {
    val h = (System.currentTimeMillis() - at) / 3_600_000
    return when { h < 1 -> "just now"; h < 24 -> "$h h ago"; else -> "${h / 24} d ago" }
}

/** 4c while running, 4d when done. */
@Composable
fun PoolRunScreen(model: AppModel) {
    val p = model.pool ?: run { model.back(); return }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.running) { while (p.running) { now = System.currentTimeMillis(); delay(500) } }
    val scores = p.ids.associateWith { p.after[it] ?: p.before[it] }
    val start = model.poolScore(p.before)
    val score = model.poolScore(scores)
    val doneIds = p.builds.keys
    if (p.running) Frame(model, Screen.Optimize) {
        BackToPrevious(model)
        ScreenTitle("Optimizing pool", "一括")
        PoolScoreCard("POOL SCORE · LIVE", start, score) { Text("+${fmtInt((score - start).toDouble())}", style = dela(18.sp, C.blue)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val secs = (now - p.startedAt) / 1000
            Text((if (p.round > 1) "Round ${p.round} · " else "") + "${doneIds.size} of ${p.ids.size} heroes" +
                " · ${secs / 60}:${"%02d".format(java.util.Locale.US, secs % 60)}",
                style = zen(12.sp), modifier = Modifier.weight(1f))
            LinkText("Stop", color = C.deepPink) { model.stopPool() }
        }
        TierBars(model, p, running = true)
        p.current?.let { id -> model.hero(id)?.let { h ->
            Card(shadow = null, radius = 14.dp, border = 2.5.dp, padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Face(model.face(h), 34.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Now: ${model.heroName(h)}", style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.before[id]?.let { "$it%" } ?: "—"} → best so far ${p.currentBest?.let { "$it%" } ?: "…"}",
                            style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.width(48.dp)) { p.currentBest?.let { MatchChip(it) } }
                }
            }
        } }
        Text("A piece moves only when the hero taking it gains more than its wearer loses, counting higher tiers more. Stop any time and keep what's done. You can leave the app; we'll notify you.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    } else Frame(null, null, actions = if (p.fromHistory) null else ({
        // moves happen in the game, so the checklist comes first; its last step records them here
        PinkButton("Checklist", Modifier.weight(1f)) {
            if (model.loadPoolChecklist()) {
                if (OverlayState.canDraw(model.getApplication())) { OverlayService.start(model.getApplication()); model.openGame() }
                else model.go(Screen.OverlayIntro)
            }
        }
        OutlineButton("Just record") { model.equipPool(); model.tab(Screen.Optimize) }
    })) {
        BackToPrevious(model)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitText("Pool done", TitleStyle, Modifier.weight(1f, fill = false)); Stamp("済")
        }
        if (p.fromHistory) HistoryNote(p.startedAt)
        val edited = if (p.fromHistory) emptyList() else model.editedSinceRun()
        if (edited.isNotEmpty()) Card(Modifier.hatch(), padding = PaddingValues(0.dp)) {
            ListRow({ model.startPool(keepCalls = true) }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), trailing = { Text("Re-run", style = zen(12.sp, color = C.deepPink)) }) {
                Text("Setup changed for ${edited.size} " + (if (edited.size == 1) "hero" else "heroes") + " since this run", style = zen(13.sp))
                // one name and a count: a list of names cut at the edge would cut a hero's name
                Text((model.hero(edited.first())?.let(model::heroName) ?: "?") + if (edited.size > 1) " + ${edited.size - 1} more" else "",
                    style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        PoolScoreCard("POOL SCORE · ${p.ids.size} HEROES", start, score) {
            Column(horizontalAlignment = Alignment.End) {
                val pct = if (start > 0) Math.round(100.0 * (score - start) / start) else 0
                Text((if (pct >= 0) "+" else "") + "$pct%", style = dela(18.sp, C.blue))
                Text("${p.moves} items move", style = zen(10.sp, FontWeight.Medium, C.muted))
            }
        }
        TierBars(model, p, running = false)
        val worse = p.builds.keys.filter { (p.after[it] ?: 0) < (p.before[it] ?: 0) }
        val low = p.builds.keys.filter { (p.after[it] ?: 100) < 60 }
        val traders = p.trades.flatMap { listOf(it.taker, it.holder) }.distinct()
        val queue = model.reviewQueue()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LinkText("Every hero · ${queue.count { it in p.reviewed }} of ${queue.size} reviewed") { model.sheet = Sheet(SheetKind.POOL_HEROES, 0) }
        }
        SectionTitle("Worth a look", trailing = "${(worse + low + traders).distinct().size} heroes")
        Card(padding = PaddingValues(0.dp)) {
            LookRow(model, worse, "${worse.size} heroes got worse",
                worse.take(3).joinToString(" · ") { id -> "${model.hero(id)?.let(model::heroName)} ${p.before[id]}→${p.after[id]}%" }, true) {
                model.sheet = Sheet(SheetKind.POOL_WORSE, 0)
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
            LookRow(model, traders, "${p.trades.size} contested " + if (p.trades.size == 1) "trade" else "trades",
                "${p.trades.count { it.made }} made · ${p.trades.count { !it.made }} not made · yours to change", false) { model.sheet = Sheet(SheetKind.POOL_TRADES, 0) }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
            LookRow(model, low, "${low.size} still below 60%", "Not enough gear · see ghost pieces", false) { model.sheet = Sheet(SheetKind.POOL_LOW, 0) }
        }
        if (p.builds.size < p.ids.size) Text("Stopped after ${p.builds.size} of ${p.ids.size} heroes; the rest keep their gear.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun LookRow(model: AppModel, ids: List<Long>, title: String, sub: String, hot: Boolean, onTap: () -> Unit) {
    ListRow(if (ids.isEmpty()) null else onTap, Modifier.then(if (hot && ids.isNotEmpty()) Modifier.hatch() else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        leading = { FaceStack(model, ids.mapNotNull { model.hero(it) }) }) {
        Text(title, style = zen(13.sp))
        Text(if (ids.isEmpty()) "None" else sub, style = zen(11.sp, color = if (hot && ids.isNotEmpty()) C.deepPink else C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TierBars(model: AppModel, p: PoolState, running: Boolean) {
    Card(padding = PaddingValues(0.dp)) {
        val byTier = p.ids.groupBy { model.tier(it) }.toSortedMap(compareByDescending { it })
        byTier.entries.forEachIndexed { k, (t, ids) ->
            if (k > 0) Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
            val done = ids.count { it in p.builds }
            val current = running && done in 1 until ids.size || (running && done == 0 && p.current in ids)
            val before = ids.mapNotNull { p.before[it] }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            val after = ids.mapNotNull { p.after[it] ?: p.before[it] }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            Column(Modifier.fillMaxWidth().then(if (current) Modifier.hatch() else Modifier).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$t", style = dela(13.sp, C.pink))
                    Text("${TIER_LABELS[t - 1]} · ${ids.size}", style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // fixed-width slot: the stamp or count appearing never moves the label
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                        when {
                            !running -> after?.let { MatchChip(it) }
                            done == ids.size -> Stamp("済")
                            current -> Text("$done of ${ids.size}", style = zen(11.sp, color = C.deepPink))
                        }
                    }
                }
                ScoreBar((after ?: 0) / 100f, before?.let { it / 100f })
                Text("${before?.let { "$it%" } ?: "—"} → ${after?.let { "$it%" } ?: "—"}", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
    }
}

/** 4b, as flexible as one-hero setup: a role to start from (no community build), then the usual preset, sets, mains, targets. */
@Composable
fun PoolHeroScreen(model: AppModel, heroId: Long, setup: Boolean) {
    model.rev
    val h = model.hero(heroId) ?: run { model.back(); return }
    // the queue is fixed on entry: a hero given a role stops counting as "no build" but must stay in the walk
    val queue = remember(setup) {
        model.poolHeroes().filter { if (setup) model.hasBuild(it.id) == false else model.hasBuild(it.id) == true }.map { it.id }
            .let { if (heroId in it) it else listOf(heroId) + it }
    }
    val i = queue.indexOf(heroId)
    // Next walks to the next hero still to review, so an interrupted walk picks up where it stopped
    val next = queue.drop(i + 1).firstOrNull { it !in model.poolSkip && !model.request(it).reviewed }?.let { model.hero(it) }
    val req = model.request(heroId)
    val noBuilds = model.presets[h.code]?.let { it.mostUsed.isEmpty() && it.rta.isEmpty() } ?: (model.presetLoading[h.code] != true)
    fun goNext() { if (next != null) model.replace(Screen.PoolHero(next.id, setup)) else model.back() }
    Frame(null, null, actions = {
        // always laid out (dimmed on the first hero) so Next keeps its place
        val prev = queue.getOrNull(i - 1)
        OutlineButton("‹ Previous", Modifier.width(118.dp), enabled = prev != null) { model.save(); prev?.let { model.replace(Screen.PoolHero(it, setup)) } }
        PinkButton(if (next != null) "Next · ${model.heroName(next)} ›" else "Done", Modifier.weight(1f), short = "Next ›".takeIf { next != null }) { req.reviewed = true; model.save(); goNext() }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackLink("Whole pool") { model.back() }
            Spacer(Modifier.weight(1f))
            // tapping the stamp takes the mark off, so this hero comes up again in the walk
            if (req.reviewed) { Box(Modifier.tap { req.reviewed = false; model.touch() }) { Stamp("済", "reviewed", C.blue) }; HSpace(8.dp) }
            Text("${i + 1} of ${queue.size}", style = zen(12.sp, color = C.muted))
        }
        Card(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Face(model.face(h), 50.dp)
                Column(Modifier.weight(1f)) {
                    Text(model.heroName(h), style = dela(17.sp).copy(lineHeight = 21.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HeroSubtitle(model, h, if (setup && req.targets.all { it.isNaN() }) "No build yet" else heroSubtitle(model, h))
                }
                LinkText(if (heroId in model.poolSkip) "Kept as is" else "Keep as is", color = C.deepPink) {
                    model.poolSkip = if (heroId in model.poolSkip) model.poolSkip - heroId else model.poolSkip + heroId
                    model.save()
                }
            }
        }
        if (noBuilds) {
            Text("START FROM A ROLE", style = zen(10.sp, color = C.muted).copy(letterSpacing = 0.6.sp))
            val picked = req.presetLabel?.takeIf { it.startsWith("Role · ") }?.removePrefix("Role · ")
            CardGrid(Role.entries) { r, cell ->
                val on = picked == r.label
                Card(cell, shadow = if (on) C.pink else null, radius = 12.dp, border = 2.5.dp, halftone = on,
                    padding = PaddingValues(10.dp), onClick = { model.applyRole(heroId, r) }) {
                    Text(r.label, style = dela(14.sp))
                    Text(r.sub, style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else PresetCard(model, h, req)
        SectionTitle("What it looks for")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { LooksFor(model, h, req) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Goal: ${req.goal.label}", style = zen(12.sp, color = C.muted))
            LinkText("More goals") { model.sheet = Sheet(SheetKind.GOALS, heroId) }
        }
        Text("Everything here works as in one-hero Optimize; the pool run uses it as set.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
fun PoolPickSheet(model: AppModel) {
    model.rev
    val f = remember { HeroFilter() }
    SheetBody("Pick heroes", button = "Done", onButton = { model.save(); model.sheet = null }) {
        Text("Heroes picked here join the run on top of the ticked tiers.", style = zen(12.sp, FontWeight.Medium, C.muted))
        HeroFilterBar(f)
        model.box?.heroes.orEmpty().filter { model.gameHero(it) != null && f.matches(model, it) }
            .sortedWith(compareByDescending<OwnedHero> { model.tier(it.id) }.thenBy { model.heroName(it) }).forEach { h ->
                val on = h.id in model.poolExtra || model.tier(h.id) in model.poolTiers
                // a tick list, not a link list: no arrow, so the row is built by hand
                Row(Modifier.fillMaxWidth().testTag(ROW_TAG).tap { model.poolExtra = if (h.id in model.poolExtra) model.poolExtra - h.id else model.poolExtra + h.id }
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(on) { model.poolExtra = if (it) model.poolExtra + h.id else model.poolExtra - h.id }
                    Face(model.face(h), 32.dp)
                    Text(model.heroName(h), style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Slot(40.dp) { TierDots(model.tier(h.id), 6.dp) }
                }
            }
    }
}

@Composable
fun PoolWorseSheet(model: AppModel) {
    val p = model.pool ?: return
    val worse = p.builds.keys.filter { (p.after[it] ?: 0) < (p.before[it] ?: 0) }
    val kept = worse.filter { it in model.poolSkip }
    SheetBody("Got worse", button = if (kept.isEmpty()) "Done" else "Re-run without ${kept.size}", onButton = {
        model.sheet = null
        if (kept.isNotEmpty()) model.startPool()
    }) {
        Text("With Losses set to Small losses, a hero may give up a little for a bigger gain elsewhere. Tap one to see what it lost and to whom; keep one as is and the pool runs again without it.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        worse.forEach { id ->
            val h = model.hero(id) ?: return@forEach
            val on = id in model.poolSkip
            ListRow({ model.openPoolHero(id) }, Modifier.padding(vertical = 5.dp), leading = { Face(model.face(h), 34.dp) }, trailing = {
                Slot(104.dp) { Pill(if (on) "Kept as is" else "Keep as is", selected = on, onClick = { model.poolSkip = if (on) model.poolSkip - id else model.poolSkip + id }) }
            }) {
                Text(model.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${p.before[id]}% → ${p.after[id]}%", style = zen(11.sp, color = C.deepPink))
            }
        }
    }
}

/** Every hero of the run, before → after; tap one to compare its new build with what it wears now. */
/** Everyone the pool run will optimize, in run order; tap one to edit it, or carry on the review where it stopped. */
@Composable
fun PoolListSheet(model: AppModel) {
    model.rev
    val heroes = model.poolMembers()
    val reviewed = heroes.count { model.request(it.id).reviewed }
    val next = heroes.firstOrNull { model.hasBuild(it.id) == true && it.id !in model.poolSkip && !model.request(it.id).reviewed }
    SheetBody("Heroes in this run", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$reviewed of ${heroes.size} reviewed", style = zen(12.sp, color = C.muted))
            // dimmed rather than hidden at 0, so the header never moves
            Box(Modifier.alpha(if (reviewed > 0) 1f else 0.35f)) {
                LinkText("Clear") { if (reviewed > 0) { heroes.forEach { model.request(it.id).reviewed = false }; model.touch() } }
            }
        }
    },
        button = next?.let { "Review next · ${model.heroName(it)}" } ?: "Done",
        onButton = { next?.let { model.go(Screen.PoolHero(it.id, setup = false)) } ?: run { model.sheet = null } }) {
        heroes.forEach { h ->
            val req = model.request(h.id)
            val has = model.hasBuild(h.id)
            ListRow({ model.go(Screen.PoolHero(h.id, setup = has == false)) }, Modifier.padding(vertical = 5.dp), leading = { Face(model.face(h), 34.dp) }, trailing = {
                Slot(26.dp) { if (req.reviewed) Text("済", style = dela(14.sp, C.blue)) }
                Slot(48.dp) { model.matchFor(h.id)?.let { MatchChip(it.pct) } }
            }) {
                Text(model.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TierDots(model.tier(h.id), 7.dp)
                    Text(when { has == null -> "Loading builds…"; has == false -> "No build yet"; h.id in model.poolSkip -> "Kept as is"
                        else -> req.presetLabel?.substringAfter(" · ") ?: "Your targets" },
                        style = zen(11.sp, FontWeight.Medium, if (has == false) C.deepPink else C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Pieces one hero of the run wanted from another: what the run did with each, and the player's call for a re-run. */
@Composable
fun PoolTradesSheet(model: AppModel) {
    model.rev
    val p = model.pool ?: return
    val changes = p.trades.count { model.tradeMoves(it) != it.made }
    SheetBody("Contested trades", button = if (changes == 0) "Done" else "Re-run with $changes " + if (changes == 1) "change" else "changes", onButton = {
        model.sheet = null
        if (changes > 0) model.startPool(keepCalls = true)
    }) {
        Text("Pieces one hero of the run wanted from another, where someone pays: a move that cost its wearer, or one not made because it would have. Pick where each goes and re-run; the rest of the pool is optimized around your picks, even if a hero ends worse.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        p.trades.forEach { t ->
            val holder = model.hero(t.holder) ?: return@forEach
            val taker = model.hero(t.taker) ?: return@forEach
            // match as the app shows it (0..100); a change that rounds away shows a decimal, since Never worse refuses even that
            fun span(p: Pair<Double, Double>): String {
                val (x, y) = p.first.coerceIn(0.0, 100.0) to p.second.coerceIn(0.0, 100.0)
                val f = if (Math.round(x) == Math.round(y) && Math.abs(x - y) > 0.05) "%.1f" else "%.0f"
                return f.format(java.util.Locale.US, x) + "→" + f.format(java.util.Locale.US, y) + "%"
            }
            val effect = when {
                t.stuck -> "Couldn't move: without " + (if (t.items.size == 1) "it" else "them") + ", ${model.heroName(holder)} has no full build at all"
                t.made -> "Moved: ${model.heroName(taker)} ${p.before[t.taker] ?: "—"}→${p.after[t.taker] ?: "—"}%, ${model.heroName(holder)} ${p.before[t.holder] ?: "—"}→${p.after[t.holder] ?: "—"}%"
                t.takerPoints.second.isNaN() -> "Stayed: your call"
                t.holderPoints.second.isNaN() -> "Stayed: without " + (if (t.items.size == 1) "it" else "them") + ", ${model.heroName(holder)} has no build as good as now"
                else -> "Stayed: moving would make ${model.heroName(taker)} ${span(t.takerPoints)}, ${model.heroName(holder)} ${span(t.holderPoints)}" +
                    if (t.moves > 0) ", ${t.moves} more " + (if (t.moves == 1) "move" else "moves") else ""
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow({ model.openPoolHero(t.taker) }, Modifier.padding(top = 5.dp), leading = { Face(model.face(holder), 34.dp) },
                    trailing = { Slot(30.dp) { Face(model.face(taker), 26.dp, ringWidth = 2.dp) } }) {
                    Text("${model.heroName(holder)} → ${model.heroName(taker)} · ${t.items.size} " + if (t.items.size == 1) "piece" else "pieces",
                        style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(effect, style = zen(11.sp, FontWeight.Medium, if (t.made) C.blue else C.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Segmented(listOf("Stays", "Moves"), if (model.tradeMoves(t)) 1 else 0) { i -> model.callTrade(t, i == 1) }
            }
        }
    }
}

@Composable
fun PoolHeroesSheet(model: AppModel) {
    val p = model.pool ?: return
    val queue = model.reviewQueue()
    // like Heroes in this run before a run: marks, and the walk resumes at the first change not looked at yet
    val next = queue.firstOrNull { it !in p.reviewed }
    SheetBody("Every hero", trailing = { Text("${queue.count { it in p.reviewed }} of ${queue.size} reviewed", style = zen(12.sp, color = C.muted)) },
        button = next?.let { "Review next · ${model.hero(it)?.let(model::heroName)}" } ?: "Done",
        onButton = { next?.let { model.go(Screen.PoolReview(it)) } ?: run { model.sheet = null } }) {
        p.ids.forEach { id ->
            val h = model.hero(id) ?: return@forEach
            val b = p.builds[id]
            // a change opens in the walk (Previous/Next from there); a hero keeping its gear opens its setup
            ListRow({ if (b != null && !p.running) model.go(Screen.PoolReview(id)) else model.openPoolHero(id) }, Modifier.padding(vertical = 5.dp),
                leading = { Face(model.face(h), 34.dp) }, trailing = {
                    Slot(26.dp) { if (id in p.reviewed) Text("済", style = dela(14.sp, C.blue)) }
                    Slot(48.dp) { p.after[id]?.let { MatchChip(it) } }
                }) {
                Text(model.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (b == null) (if (p.running) "Not run" else "Keeps its gear") else "${p.before[id]?.let { "$it%" } ?: "—"} → ${p.after[id]?.let { "$it%" } ?: "—"} · " +
                    "${b.items.count { it.equippedOn != id }} changes", style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PoolLowSheet(model: AppModel) {
    val p = model.pool ?: return
    val low = p.builds.keys.filter { (p.after[it] ?: 100) < 60 }
    SheetBody("Still below 60%", button = "Done", onButton = { model.sheet = null }) {
        Text("These heroes don't have the gear yet. Open one to see which single piece (a ghost) would help most.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        low.forEach { id ->
            val h = model.hero(id) ?: return@forEach
            ListRow({ model.pickHero(id); model.team = listOf(id); model.startSearch(); model.go(Screen.Results) }, Modifier.padding(vertical = 5.dp),
                leading = { Face(model.face(h), 34.dp) }, trailing = { Slot(48.dp) { p.after[id]?.let { MatchChip(it) } } }) {
                Text(model.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
