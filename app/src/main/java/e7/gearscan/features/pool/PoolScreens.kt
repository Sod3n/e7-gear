package e7.gearscan.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import e7.gearscan.features.pool.PoolState
import e7.gearscan.features.pool.POOL_RULES
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Role
import e7.gearscan.data.fmtInt
import kotlinx.coroutines.delay

/** 4a: the Optimize screen's "Whole pool" mode. */
@Composable
@OptIn(LinkStyle::class)
fun PoolSetup(graph: AppGraph) {
    // drawn inside Frame's content, which skips unless it reads the change counter itself
    graph.scan.rev
    val heroes = graph.pool.heroes()
    LaunchedEffect(heroes.map { it.id }) { heroes.forEach { graph.heroes.loadPresetsQuietly(it.id, network = true) } }
    val now = heroes.associate { it.id to graph.heroes.matchFor(it.id)?.pct }
    StaleBox(graph)
    PendingCard(graph)
    PoolScoreCard("POOL SCORE · ${heroes.size} HEROES", null, graph.pool.score(now), note = graph.pool.lastPool?.let { (at, _) -> "last run ${ago(at)}" }) {}
    RowCard("Rules", { graph.nav.sheet = Sheet(SheetKind.ADVANCED, POOL_RULES) }) {
        val reqs = heroes.map { graph.heroes.request(it.id) }
        fun <T> same(f: (Request) -> T) = reqs.map(f).distinct().singleOrNull()
        Text(listOf(
            same { it.minEnhance }?.let { "+$it gear" } ?: "mixed levels",
            when (same { it.mods }) { true -> "mods on"; false -> "mods off"; null -> "mods for some" },
            when (same { it.capCrit }) { true -> "crit cap 105"; false -> "no crit cap"; null -> "crit cap for some" },
        ).joinToString(" · "), style = zen(12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    SectionTitle("Which heroes", trailing = "by tier")
    Card(padding = PaddingValues(0.dp)) {
        val all = graph.scan.box?.heroes.orEmpty().filter { graph.heroes.gameHero(it) != null }
        for (t in 5 downTo 1) {
            val inTier = all.filter { graph.heroes.tier(it.id) == t }
            if (inTier.isEmpty() && t < 5) continue
            val on = t in graph.pool.tiers
            Row(Modifier.fillMaxWidth().tap { graph.pool.tiers = if (on) graph.pool.tiers - t else graph.pool.tiers + t; graph.store.saveNow() }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(on) { graph.pool.tiers = if (it) graph.pool.tiers + t else graph.pool.tiers - t; graph.store.saveNow() }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$t", style = dela(13.sp, C.pink)); Text(e7.gearscan.ui.TIER_LABELS[t - 1], style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val avg = inTier.mapNotNull { graph.heroes.matchFor(it.id)?.pct }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                    Text("${inTier.size} heroes" + (avg?.let { " · now $it%" } ?: ""), style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FaceStack(graph, inTier)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.5.dp).background(C.line))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (graph.pool.extra.isEmpty()) "Or pick heroes one by one" else "${graph.pool.extra.size} picked one by one",
                style = zen(12.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f))
            LinkText("Choose") { graph.nav.sheet = Sheet(SheetKind.POOL_PICK, 0) }
        }
    }
    val pending = heroes.count { graph.heroes.hasBuild(it.id) == null }
    val noBuild = heroes.filter { graph.heroes.hasBuild(it.id) == false }
    val ready = heroes.count { graph.heroes.hasBuild(it.id) == true }
    Card(padding = PaddingValues(0.dp)) {
        val reviewed = heroes.count { graph.heroes.hasBuild(it.id) == true && graph.heroes.request(it.id).reviewed }
        ListRow(if (ready > 0) ({ graph.nav.sheet = Sheet(SheetKind.POOL_LIST, 0) }) else null, Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(C.blue)) }) {
            Text(if (pending > 0) "$ready ready · loading builds for $pending" else "$ready use their preset · $reviewed reviewed",
                style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (noBuild.isNotEmpty()) ListRow({ graph.nav.go(Screen.PoolHero(noBuild.first().id, setup = true)) },
            Modifier.hatch().padding(horizontal = 12.dp, vertical = 11.dp), leading = { Box(Modifier.size(8.dp).clip(CircleShape).background(C.pink)) },
            trailing = { Text("Set up", style = zen(12.sp, color = C.deepPink)) }) {
            Text("${noBuild.size} have no build yet", style = zen(13.sp))
        }
    }
}

/** The last run, until it's equipped or another run replaces it. */
@Composable
private fun PendingCard(graph: AppGraph) {
    val at = graph.pool.pending ?: return
    val e = graph.history.entries.firstOrNull { it.at == at } ?: return
    Card(shadow = C.pink, padding = PaddingValues(0.dp)) {
        ListRow({ graph.pool.openPending() }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Pending run · ${historyStamp(at)}", style = zen(13.sp))
            Text("${fmtInt(e.before.toDouble())} → ${fmtInt(e.after.toDouble())} · ${e.builds.size} to equip · ${e.reviewed.size} reviewed. Stays until you equip it or run again.",
                style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 4a's button: hero count plus a time estimate. */
@Composable
fun poolAction(graph: AppGraph): Action {
    graph.scan.rev
    val heroes = graph.pool.heroes()
    val ready = heroes.count { graph.heroes.hasBuild(it.id) == true }
    val skipped = heroes.count { graph.heroes.hasBuild(it.id) == false }
    val seconds = ready * graph.pool.secondsPerHero
    val time = if (seconds < 60) "about ${maxOf(5, Math.round(seconds / 5).toInt() * 5)} s" else "about ${Math.round(seconds / 60)} min"
    return Action("Optimize $ready heroes", enabled = ready > 0, detail = time + if (skipped > 0) " · $skipped skipped unless set up" else "") {
        if (graph.pool.start()) graph.nav.go(Screen.PoolRun)
    }
}

@Composable
private fun FaceStack(graph: AppGraph, heroes: List<OwnedHero>) {
    Box(Modifier.width(62.dp).height(30.dp)) {
        heroes.take(2).forEachIndexed { i, h -> Box(Modifier.offset((i * 16).dp, 0.dp)) { Face(graph.heroes.face(h), 30.dp, ringWidth = 2.dp) } }
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
    Box(Modifier.fillMaxWidth().height(9.dp).clip(shape).background(C.card).border(2.dp, C.edge, shape)) {
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
@OptIn(LinkStyle::class)
fun PoolRunScreen(graph: AppGraph) {
    val p = graph.pool.state ?: run { graph.nav.back(); return }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.running) { while (p.running) { now = System.currentTimeMillis(); delay(500) } }
    val scores = p.ids.associateWith { p.after[it] ?: p.before[it] }
    val start = graph.pool.score(p.before)
    val score = graph.pool.score(scores)
    val doneIds = p.builds.keys
    if (p.running) Frame(graph, Screen.Optimize) {
        BackToPrevious()
        ScreenTitle("Optimizing pool", "一括")
        PoolScoreCard("POOL SCORE · LIVE", start, score) { Text("+${fmtInt((score - start).toDouble())}", style = dela(18.sp, C.blue)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val secs = (now - p.startedAt) / 1000
            Text((if (p.round > 1) "Round ${p.round} · " else "") + "${doneIds.size} of ${p.ids.size} heroes" +
                " · ${secs / 60}:${"%02d".format(java.util.Locale.US, secs % 60)}",
                style = zen(12.sp), modifier = Modifier.weight(1f))
            LinkText("Stop", color = C.deepPink) { graph.pool.stop() }
        }
        TierBars(graph, p, running = true)
        p.current?.let { id -> graph.heroes.hero(id)?.let { h ->
            Card(shadow = null, radius = 14.dp, border = 2.5.dp, padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Face(graph.heroes.face(h), 34.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Now: ${graph.heroes.heroName(h)}", style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.before[id]?.let { "$it%" } ?: "—"} → best so far ${p.currentBest?.let { "$it%" } ?: "…"}",
                            style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.width(48.dp)) { p.currentBest?.let { MatchChip(it) } }
                }
            }
        } }
        Text("A piece moves only when the hero taking it gains more than its wearer loses, counting higher tiers more. Stop any time and keep what's done. You can leave the app; we'll notify you.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    } else Frame(null, null,
        // moves happen in the game, so the checklist comes first; its last step records them here
        primary = Action("Checklist") {
            if (graph.checklist.loadPool()) {
                graph.overGame.open()
            }
        },
        secondary = if (!p.fromHistory) Secondary("Just record") { graph.pool.equip(); graph.nav.tab(Screen.Optimize) } else null) {
        BackToPrevious()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitText("Pool done", TitleStyle, Modifier.weight(1f, fill = false)); Stamp("済")
        }
        if (p.fromHistory) HistoryNote(p.startedAt)
        val edited = if (p.fromHistory) emptyList() else graph.pool.editedSinceRun()
        if (edited.isNotEmpty()) Card(Modifier.hatch(), padding = PaddingValues(0.dp)) {
            ListRow({ graph.pool.start(keepCalls = true) }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), trailing = { Text("Re-run", style = zen(12.sp, color = C.deepPink)) }) {
                Text("Setup changed for ${edited.size} " + (if (edited.size == 1) "hero" else "heroes") + " since this run", style = zen(13.sp))
                // one name and a count: a list of names cut at the edge would cut a hero's name
                Text((graph.heroes.hero(edited.first())?.let(graph.heroes::heroName) ?: "?") + if (edited.size > 1) " + ${edited.size - 1} more" else "",
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
        TierBars(graph, p, running = false)
        val worse = p.builds.keys.filter { (p.after[it] ?: 0) < (p.before[it] ?: 0) }
        val low = p.builds.keys.filter { (p.after[it] ?: 100) < 60 }
        val traders = p.trades.flatMap { listOf(it.taker, it.holder) }.distinct()
        val queue = graph.pool.reviewQueue()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LinkText("Every hero · ${queue.count { it in p.reviewed }} of ${queue.size} reviewed") { graph.nav.sheet = Sheet(SheetKind.POOL_HEROES, 0) }
        }
        SectionTitle("Worth a look", trailing = "${(worse + low + traders).distinct().size} heroes")
        Card(padding = PaddingValues(0.dp)) {
            LookRow(graph, worse, "${worse.size} heroes got worse",
                worse.take(3).joinToString(" · ") { id -> "${graph.heroes.hero(id)?.let(graph.heroes::heroName)} ${p.before[id]}→${p.after[id]}%" }, true) {
                graph.nav.sheet = Sheet(SheetKind.POOL_WORSE, 0)
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
            LookRow(graph, traders, "${p.trades.size} contested " + if (p.trades.size == 1) "trade" else "trades",
                "${p.trades.count { it.made }} made · ${p.trades.count { !it.made }} not made · yours to change", false) { graph.nav.sheet = Sheet(SheetKind.POOL_TRADES, 0) }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
            LookRow(graph, low, "${low.size} still below 60%", "Not enough gear · see ghost pieces", false) { graph.nav.sheet = Sheet(SheetKind.POOL_LOW, 0) }
        }
        if (p.builds.size < p.ids.size) Text("Stopped after ${p.builds.size} of ${p.ids.size} heroes; the rest keep their gear.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun LookRow(graph: AppGraph, ids: List<Long>, title: String, sub: String, hot: Boolean, onTap: () -> Unit) {
    ListRow(if (ids.isEmpty()) null else onTap, Modifier.then(if (hot && ids.isNotEmpty()) Modifier.hatch() else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        leading = { FaceStack(graph, ids.mapNotNull { graph.heroes.hero(it) }) }) {
        Text(title, style = zen(13.sp))
        Text(if (ids.isEmpty()) "None" else sub, style = zen(11.sp, color = if (hot && ids.isNotEmpty()) C.deepPink else C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TierBars(graph: AppGraph, p: PoolState, running: Boolean) {
    Card(padding = PaddingValues(0.dp)) {
        val byTier = p.ids.groupBy { graph.heroes.tier(it) }.toSortedMap(compareByDescending { it })
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
@OptIn(LinkStyle::class)
fun PoolHeroScreen(graph: AppGraph, heroId: Long, setup: Boolean) {
    graph.scan.rev
    val h = graph.heroes.hero(heroId) ?: run { graph.nav.back(); return }
    // the queue is fixed on entry: a hero given a role stops counting as "no build" but must stay in the walk
    val queue = remember(setup) {
        graph.pool.heroes().filter { if (setup) graph.heroes.hasBuild(it.id) == false else graph.heroes.hasBuild(it.id) == true }.map { it.id }
            .let { if (heroId in it) it else listOf(heroId) + it }
    }
    val i = queue.indexOf(heroId)
    // Next walks to the next hero still to review, so an interrupted walk picks up where it stopped
    val next = queue.drop(i + 1).firstOrNull { it !in graph.pool.skip && !graph.heroes.request(it).reviewed }?.let { graph.heroes.hero(it) }
    val req = graph.heroes.request(heroId)
    val noBuilds = graph.heroes.presets[h.code]?.let { it.mostUsed.isEmpty() && it.rta.isEmpty() } ?: (graph.heroes.presetLoading[h.code] != true)
    fun goNext() { if (next != null) graph.nav.replace(Screen.PoolHero(next.id, setup)) else graph.nav.back() }
    val prev = queue.getOrNull(i - 1)
    Frame(null, null,
        primary = Action(if (next != null) "Next · ${graph.heroes.heroName(next)} ›" else "Done", short = "Next ›".takeIf { next != null }) { req.reviewed = true; graph.store.saveNow(); goNext() },
        // always laid out (dimmed on the first hero) so Next keeps its place
        secondary = Secondary("‹ Previous", Span.Fixed(118.dp), enabled = prev != null, before = true) { graph.store.saveNow(); prev?.let { graph.nav.replace(Screen.PoolHero(it, setup)) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackToPrevious(Modifier.weight(1f))
            // tapping the stamp takes the mark off, so this hero comes up again in the walk
            if (req.reviewed) { Box(Modifier.tap { req.reviewed = false; graph.scan.touch() }) { Stamp("済", "reviewed", C.blue) }; HSpace(8.dp) }
            Text("${i + 1} of ${queue.size}", style = zen(12.sp, color = C.muted))
        }
        Card(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Face(graph.heroes.face(h), 50.dp)
                Column(Modifier.weight(1f)) {
                    Text(graph.heroes.heroName(h), style = dela(17.sp).copy(lineHeight = 21.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HeroSubtitle(graph, h, if (setup && req.targets.all { it.isNaN() }) "No build yet" else heroSubtitle(graph, h))
                }
                LinkText(if (heroId in graph.pool.skip) "Kept as is" else "Keep as is", color = C.deepPink) {
                    graph.pool.skip = if (heroId in graph.pool.skip) graph.pool.skip - heroId else graph.pool.skip + heroId
                    graph.store.saveNow()
                }
            }
        }
        if (noBuilds) {
            Text("START FROM A ROLE", style = zen(10.sp, color = C.muted).copy(letterSpacing = 0.6.sp))
            val picked = req.presetLabel?.takeIf { it.startsWith("Role · ") }?.removePrefix("Role · ")
            CardGrid(Role.entries) { r, cell ->
                val on = picked == r.label
                Card(cell, shadow = if (on) C.pink else null, radius = 12.dp, border = 2.5.dp, halftone = on,
                    padding = PaddingValues(10.dp), onClick = { graph.heroes.applyRole(heroId, r) }) {
                    Text(r.label, style = dela(14.sp))
                    Text(r.sub, style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else PresetCard(graph, h, req)
        SectionTitle("What it looks for")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { LooksFor(graph, h, req) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Goal: ${req.goal.label}", style = zen(12.sp, color = C.muted))
            LinkText("More goals") { graph.nav.sheet = Sheet(SheetKind.GOALS, heroId) }
        }
        Text("Everything here works as in one-hero Optimize; the pool run uses it as set.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
fun PoolPickSheet(graph: AppGraph) {
    graph.scan.rev
    val f = remember { HeroFilter() }
    SheetBody("Pick heroes", button = "Done", onButton = { graph.store.saveNow(); graph.nav.sheet = null }) {
        Text("Heroes picked here join the run on top of the ticked tiers.", style = zen(12.sp, FontWeight.Medium, C.muted))
        HeroFilterBar(f)
        graph.scan.box?.heroes.orEmpty().filter { graph.heroes.gameHero(it) != null && f.matches(graph, it) }
            .sortedWith(compareByDescending<OwnedHero> { graph.heroes.tier(it.id) }.thenBy { graph.heroes.heroName(it) }).forEach { h ->
                val on = h.id in graph.pool.extra || graph.heroes.tier(h.id) in graph.pool.tiers
                // a tick list, not a link list: no arrow, so the row is built by hand
                Row(Modifier.fillMaxWidth().testTag(ROW_TAG).tap { graph.pool.extra = if (h.id in graph.pool.extra) graph.pool.extra - h.id else graph.pool.extra + h.id }
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(on) { graph.pool.extra = if (it) graph.pool.extra + h.id else graph.pool.extra - h.id }
                    Face(graph.heroes.face(h), 32.dp)
                    Text(graph.heroes.heroName(h), style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Slot(40.dp) { TierDots(graph.heroes.tier(h.id), 6.dp) }
                }
            }
    }
}

@Composable
fun PoolWorseSheet(graph: AppGraph) {
    val p = graph.pool.state ?: return
    val worse = p.builds.keys.filter { (p.after[it] ?: 0) < (p.before[it] ?: 0) }
    val kept = worse.filter { it in graph.pool.skip }
    SheetBody("Got worse", button = if (kept.isEmpty()) "Done" else "Re-run without ${kept.size}", onButton = {
        graph.nav.sheet = null
        if (kept.isNotEmpty()) graph.pool.start()
    }) {
        Text("With Losses set to Small losses, a hero may give up a little for a bigger gain elsewhere. Tap one to see what it lost and to whom; keep one as is and the pool runs again without it.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        worse.forEach { id ->
            val h = graph.heroes.hero(id) ?: return@forEach
            val on = id in graph.pool.skip
            ListRow({ graph.pool.open(id) }, Modifier.padding(vertical = 5.dp), leading = { Face(graph.heroes.face(h), 34.dp) }, trailing = {
                Slot(104.dp) { Pill(if (on) "Kept as is" else "Keep as is", selected = on, onClick = { graph.pool.skip = if (on) graph.pool.skip - id else graph.pool.skip + id }) }
            }) {
                Text(graph.heroes.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${p.before[id]}% → ${p.after[id]}%", style = zen(11.sp, color = C.deepPink))
            }
        }
    }
}

/** Every hero of the run, before → after; tap one to compare its new build with what it wears now. */
/** Everyone the pool run will optimize, in run order; tap one to edit it, or carry on the review where it stopped. */
@Composable
@OptIn(LinkStyle::class)
fun PoolListSheet(graph: AppGraph) {
    graph.scan.rev
    val heroes = graph.pool.members()
    val reviewed = heroes.count { graph.heroes.request(it.id).reviewed }
    val next = heroes.firstOrNull { graph.heroes.hasBuild(it.id) == true && it.id !in graph.pool.skip && !graph.heroes.request(it.id).reviewed }
    SheetBody("Heroes in this run", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$reviewed of ${heroes.size} reviewed", style = zen(12.sp, color = C.muted))
            // dimmed rather than hidden at 0, so the header never moves
            Box(Modifier.alpha(if (reviewed > 0) 1f else 0.35f)) {
                LinkText("Clear") { if (reviewed > 0) { heroes.forEach { graph.heroes.request(it.id).reviewed = false }; graph.scan.touch() } }
            }
        }
    },
        button = next?.let { "Review next · ${graph.heroes.heroName(it)}" } ?: "Done",
        onButton = { next?.let { graph.nav.go(Screen.PoolHero(it.id, setup = false)) } ?: run { graph.nav.sheet = null } }) {
        heroes.forEach { h ->
            val req = graph.heroes.request(h.id)
            val has = graph.heroes.hasBuild(h.id)
            ListRow({ graph.nav.go(Screen.PoolHero(h.id, setup = has == false)) }, Modifier.padding(vertical = 5.dp), leading = { Face(graph.heroes.face(h), 34.dp) }, trailing = {
                Slot(26.dp) { if (req.reviewed) Text("済", style = dela(14.sp, C.blue)) }
                Slot(48.dp) { graph.heroes.matchFor(h.id)?.let { MatchChip(it.pct) } }
            }) {
                Text(graph.heroes.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TierDots(graph.heroes.tier(h.id), 7.dp)
                    Text(when { has == null -> "Loading builds…"; has == false -> "No build yet"; h.id in graph.pool.skip -> "Kept as is"
                        else -> req.presetLabel?.substringAfter(" · ") ?: "Your targets" },
                        style = zen(11.sp, FontWeight.Medium, if (has == false) C.deepPink else C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Pieces one hero of the run wanted from another: what the run did with each, and the player's call for a re-run. */
@Composable
fun PoolTradesSheet(graph: AppGraph) {
    graph.scan.rev
    val p = graph.pool.state ?: return
    val changes = p.trades.count { graph.pool.tradeMoves(it) != it.made }
    SheetBody("Contested trades", button = if (changes == 0) "Done" else "Re-run with $changes " + if (changes == 1) "change" else "changes", onButton = {
        graph.nav.sheet = null
        if (changes > 0) graph.pool.start(keepCalls = true)
    }) {
        Text("Pieces one hero of the run wanted from another, where someone pays: a move that cost its wearer, or one not made because it would have. Pick where each goes and re-run; the rest of the pool is optimized around your picks, even if a hero ends worse.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        p.trades.forEach { t ->
            val holder = graph.heroes.hero(t.holder) ?: return@forEach
            val taker = graph.heroes.hero(t.taker) ?: return@forEach
            // match as the app shows it (0..100); a change that rounds away shows a decimal, since Never worse refuses even that
            fun span(p: Pair<Double, Double>): String {
                val (x, y) = p.first.coerceIn(0.0, 100.0) to p.second.coerceIn(0.0, 100.0)
                val f = if (Math.round(x) == Math.round(y) && Math.abs(x - y) > 0.05) "%.1f" else "%.0f"
                return f.format(java.util.Locale.US, x) + "→" + f.format(java.util.Locale.US, y) + "%"
            }
            val effect = when {
                t.stuck -> "Couldn't move: without " + (if (t.items.size == 1) "it" else "them") + ", ${graph.heroes.heroName(holder)} has no full build at all"
                t.made -> "Moved: ${graph.heroes.heroName(taker)} ${p.before[t.taker] ?: "—"}→${p.after[t.taker] ?: "—"}%, ${graph.heroes.heroName(holder)} ${p.before[t.holder] ?: "—"}→${p.after[t.holder] ?: "—"}%"
                t.takerPoints.second.isNaN() -> "Stayed: your call"
                t.holderPoints.second.isNaN() -> "Stayed: without " + (if (t.items.size == 1) "it" else "them") + ", ${graph.heroes.heroName(holder)} has no build as good as now"
                else -> "Stayed: moving would make ${graph.heroes.heroName(taker)} ${span(t.takerPoints)}, ${graph.heroes.heroName(holder)} ${span(t.holderPoints)}" +
                    if (t.moves > 0) ", ${t.moves} more " + (if (t.moves == 1) "move" else "moves") else ""
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow({ graph.pool.open(t.taker) }, Modifier.padding(top = 5.dp), leading = { Face(graph.heroes.face(holder), 34.dp) },
                    trailing = { Slot(30.dp) { Face(graph.heroes.face(taker), 26.dp, ringWidth = 2.dp) } }) {
                    Text("${graph.heroes.heroName(holder)} → ${graph.heroes.heroName(taker)} · ${t.items.size} " + if (t.items.size == 1) "piece" else "pieces",
                        style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(effect, style = zen(11.sp, FontWeight.Medium, if (t.made) C.blue else C.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Segmented(listOf("Stays", "Moves"), if (graph.pool.tradeMoves(t)) 1 else 0) { i -> graph.pool.callTrade(t, i == 1) }
            }
        }
    }
}

@Composable
fun PoolHeroesSheet(graph: AppGraph) {
    val p = graph.pool.state ?: return
    val queue = graph.pool.reviewQueue()
    // like Heroes in this run before a run: marks, and the walk resumes at the first change not looked at yet
    val next = queue.firstOrNull { it !in p.reviewed }
    SheetBody("Every hero", trailing = { Text("${queue.count { it in p.reviewed }} of ${queue.size} reviewed", style = zen(12.sp, color = C.muted)) },
        button = next?.let { "Review next · ${graph.heroes.hero(it)?.let(graph.heroes::heroName)}" } ?: "Done",
        onButton = { next?.let { graph.nav.go(Screen.PoolReview(it)) } ?: run { graph.nav.sheet = null } }) {
        p.ids.forEach { id ->
            val h = graph.heroes.hero(id) ?: return@forEach
            val b = p.builds[id]
            // a change opens in the walk (Previous/Next from there); a hero keeping its gear opens its setup
            ListRow({ if (b != null && !p.running) graph.nav.go(Screen.PoolReview(id)) else graph.pool.open(id) }, Modifier.padding(vertical = 5.dp),
                leading = { Face(graph.heroes.face(h), 34.dp) }, trailing = {
                    Slot(26.dp) { if (id in p.reviewed) Text("済", style = dela(14.sp, C.blue)) }
                    Slot(48.dp) { p.after[id]?.let { MatchChip(it) } }
                }) {
                Text(graph.heroes.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (b == null) (if (p.running) "Not run" else "Keeps its gear") else "${p.before[id]?.let { "$it%" } ?: "—"} → ${p.after[id]?.let { "$it%" } ?: "—"} · " +
                    "${b.items.count { it.equippedOn != id }} changes", style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PoolLowSheet(graph: AppGraph) {
    val p = graph.pool.state ?: return
    val low = p.builds.keys.filter { (p.after[it] ?: 100) < 60 }
    SheetBody("Still below 60%", button = "Done", onButton = { graph.nav.sheet = null }) {
        Text("These heroes don't have the gear yet. Open one to see which single piece (a ghost) would help most.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        low.forEach { id ->
            val h = graph.heroes.hero(id) ?: return@forEach
            ListRow({ graph.heroes.pickHero(id); graph.heroes.team = listOf(id); graph.optimize.startSearch(); graph.nav.go(Screen.Results) }, Modifier.padding(vertical = 5.dp),
                leading = { Face(graph.heroes.face(h), 34.dp) }, trailing = { Slot(48.dp) { p.after[id]?.let { MatchChip(it) } } }) {
                Text(graph.heroes.heroName(h), style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
