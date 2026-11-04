package e7.gearscan.ui

import androidx.compose.runtime.setValue
import e7.gearscan.value.Chance
import androidx.compose.runtime.getValue
import e7.gearscan.features.scan.ScanPhase
import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.calc.Calc
import e7.gearscan.calc.Reforge
import e7.gearscan.calc.Rating
import e7.gearscan.data.Goal
import e7.gearscan.data.Item
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Request
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtInt
import e7.gearscan.data.fmtK
import e7.gearscan.data.fmtNum
import e7.gearscan.data.fmtSigned
import e7.gearscan.opt.Build
import e7.gearscan.opt.Ghost
import kotlinx.coroutines.delay

@Composable
fun OptimizeScreen(graph: AppGraph) {
    graph.rev.value
    val box = graph.scan.box
    val team = graph.heroes.team.mapNotNull { graph.heroes.hero(it) }
    val pool = box != null && graph.pool.mode
    val history = box != null && graph.history.shown
    Frame(graph, Screen.Optimize, primary = when {
        history -> null
        pool -> poolAction(graph)
        team.isEmpty() -> null
        else -> Action("Search", enabled = team.all { graph.heroes.gameHero(it) != null }) {
            graph.optimize.startSearch()
            if (team.size == 1) graph.nav.go(Screen.Results)
        }
    }) {
        ScreenTitle("Optimize", "最適化") { ScanChip(graph.scan.scannedLabel(), graph.scan.phase != ScanPhase.IDLE) { if (graph.scan.phase != ScanPhase.IDLE) graph.nav.go(Screen.Capture) else graph.nav.go(Screen.ScanData) } }
        CrashCard(graph)
        graph.appUpdate.release?.let { u ->
            val p = graph.appUpdate.progress
            Card(shadow = C.pink, padding = PaddingValues(0.dp)) {
                ListRow({ graph.appUpdate.update() }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), trailing = {
                    Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(if (p != null) "${(p * 100).toInt()}%" else if (u.apk != null) "Update" else "Download", style = zen(12.sp, color = C.deepPink))
                    }
                }) {
                    Text("E7 Gear ${u.version} is out", style = zen(13.sp))
                    Text(graph.appUpdate.problem ?: "You have ${graph.appUpdate.version}. Your heroes and builds stay.",
                        style = zen(11.sp, FontWeight.Medium, if (graph.appUpdate.problem != null) C.deepPink else C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (box != null) Segmented(listOf("One hero", "Whole pool", "History"), if (graph.history.shown) 2 else if (graph.pool.mode) 1 else 0) {
            graph.history.shown = it == 2
            if (it < 2) graph.pool.mode = it == 1
        }
        if (box != null && graph.pool.state?.running == true) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.ink)
            .tap { graph.nav.go(Screen.PoolRun) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(8.dp)
            Text("Pool running · ${graph.pool.state!!.builds.size} of ${graph.pool.state!!.ids.size}", style = zen(12.sp, color = C.card), modifier = Modifier.weight(1f))
            Text("Open ›", style = zen(12.sp, color = C.card))
        }
        when {
            box == null -> NoBox(graph)
            history -> HistoryList(graph)
            pool -> PoolSetup(graph)
            team.isEmpty() -> Card {
                Text("Choose a hero to optimize", style = dela(16.sp))
                VSpace(8.dp)
                OutlineButton("Choose hero", height = 40.dp) { graph.nav.sheet = Sheet(SheetKind.HERO_PICK, 0) }
            }
            else -> SingleSetup(graph, team[0])
        }
    }
}

@Composable
@OptIn(LinkStyle::class)
private fun NoBox(graph: AppGraph) {
    Card {
        Text("No heroes yet", style = dela(17.sp))
        VSpace(4.dp)
        Text("Scan the game once to load your heroes and gear, or import a Fribbels gear.txt.", style = zen(13.sp, FontWeight.Medium, C.muted))
        VSpace(10.dp)
        CardAction(Action("Scan the game") { graph.nav.go(if (graph.settings.consent) Screen.ScanData else Screen.ScanIntro) })
        VSpace(8.dp)
        LinkText("Restore a backup or import a gear.txt") { graph.nav.go(Screen.ScanData) }
    }
}

/** The artifact icon beside the subtitle line on hero cards. */
@Composable
fun HeroSubtitle(graph: AppGraph, h: OwnedHero, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ArtifactIcon(graph, graph.content.content.artifact(graph.heroes.config(h.id).artifact ?: graph.heroes.request(h.id).artifact), 18.dp)
        Text(text, style = zen(12.sp, FontWeight.Medium, C.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

fun heroSubtitle(graph: AppGraph, h: OwnedHero): String {
    val cfg = graph.heroes.config(h.id)
    val art = graph.content.content.artifact(cfg.artifact ?: graph.heroes.request(h.id).artifact)
    return (art?.let { "${it.name} +30" } ?: "Artifact +30") + " · full imprint, always"
}

@Composable
@OptIn(LinkStyle::class)
private fun SingleSetup(graph: AppGraph, h: OwnedHero) {
    val req = graph.heroes.request(h.id)
    Card(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Face(graph.heroes.face(h), 50.dp)
            Column(Modifier.weight(1f)) {
                Text(graph.heroes.heroName(h), style = dela(17.sp).copy(lineHeight = 21.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                HeroSubtitle(graph, h, heroSubtitle(graph, h))
            }
            Pill("Change", onClick = { graph.nav.sheet = Sheet(SheetKind.HERO_PICK, h.id) })
        }
        if (graph.heroes.gameHero(h) == null) {
            VSpace(6.dp)
            Text("No game data for this hero yet. It arrives with the next content update.", style = zen(12.sp, color = C.red))
        }
    }
    PresetCard(graph, h, req)
    SectionTitle("What it looks for")
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { LooksFor(graph, h, req) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Goal: ${req.goal.label}", style = zen(12.sp, color = C.muted))
        LinkText("More goals") { graph.nav.sheet = Sheet(SheetKind.GOALS, h.id) }
    }
}

@Composable
@OptIn(LinkStyle::class)
internal fun PresetCard(graph: AppGraph, h: OwnedHero, req: Request) {
    val loading = graph.heroes.presetLoading[h.code] == true
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().hardShadow(C.pink, 3.dp, 3.dp, 14.dp).clip(shape).background(C.card).border(2.5.dp, C.edge, shape)
        .tap { graph.nav.sheet = Sheet(SheetKind.PRESET, h.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tag("PRESET")
        Column(Modifier.weight(1f)) {
            when {
                req.presetLabel != null -> {
                    Text(req.presetLabel ?: "", style = zen(13.sp))
                    Text("${if (req.presetAuto) "Picked as closest to your gear" else "From Fribbels' hero library"} · ${req.presetShare}% of players",
                        style = zen(11.sp, FontWeight.Medium, C.muted))
                }
                loading -> {
                    Text("Loading community builds…", style = zen(13.sp))
                    Skeleton(Modifier.padding(top = 4.dp).width(140.dp).height(8.dp))
                }
                graph.heroes.presets.containsKey(h.code) && graph.heroes.presets[h.code] == null -> {
                    Text("No preset", style = zen(13.sp))
                    Text("Community builds couldn't load; set targets yourself", style = zen(11.sp, FontWeight.Medium, C.muted))
                }
                else -> Text("Choose a preset", style = zen(13.sp))
            }
        }
        LinkText("Change") { graph.nav.sheet = Sheet(SheetKind.PRESET, h.id) }
    }
}

@Composable
fun SetsSummary(graph: AppGraph, req: Request) {
    SetChoiceSummary(graph, e7.gearscan.data.SetChoice.from(req.setAlts, graph.content.content), 20.dp)
}

/** All eight stats in fixed 4×2 cells (same as preset cards); a stat with a target is blue once met, pink while short. */
@Composable
fun StatGrid(req: Request, value: (Stat) -> Double) {
    CardGrid(Stat.entries, columns = 4, gap = 2.dp) { st, cell ->
        val t = req.targets[st.ordinal]; val v = value(st)
        val color = when { t.isNaN() -> C.muted; v >= t - 0.5 -> C.blue; else -> C.deepPink }
        Box(cell) { StatValue(st, fmtK(Math.floor(v)), color, 11) }
    }
}

@Composable
internal fun LooksFor(graph: AppGraph, h: OwnedHero, req: Request) {
    RowCard("Artifact", { graph.nav.sheet = Sheet(SheetKind.ARTIFACT, h.id) }) {
        val art = graph.content.content.artifact(graph.heroes.config(h.id).artifact ?: req.artifact)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtifactIcon(graph, art, 20.dp)
            Text(art?.name ?: "None chosen", style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    RowCard("Sets", { graph.nav.sheet = Sheet(SheetKind.SETS, h.id) }) { SetsSummary(graph, req) }
    RowCard("Mains", { graph.nav.sheet = Sheet(SheetKind.MAINS, h.id) }) {
        if (req.mains.isEmpty()) Text("Any", style = zen(12.sp))
        for (s in Slot.ACCESSORIES) {
            val m = req.mains[s] ?: continue
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (s == Slot.NECKLACE) "Neck" else s.label, style = zen(12.sp))
                m.take(2).forEach { g ->
                    StatIcon(g.stat)
                    if (g.percentOfBase) Text("%", style = zen(12.sp))
                }
            }
        }
    }
    RowCard("Targets", { graph.nav.sheet = Sheet(SheetKind.TARGETS, h.id) }) {
        val set = Stat.entries.filter { !req.targets[it.ordinal].isNaN() }
        if (set.isEmpty()) Text(if (req.goal == Goal.TARGETS) "None · ranks by gear score" else "None", style = zen(12.sp))
        // every target in a fixed stat order (the row wraps), so a weight change never adds, drops or reorders one
        set.forEach { s -> StatValue(s, fmtK(req.targets[s.ordinal])) }
    }
    val cur = graph.heroes.currentBuild(h.id)
    if (cur != null) RowCard("Now", { graph.nav.sheet = Sheet(SheetKind.TARGETS, h.id) }) { StatGrid(req) { cur.final[it] } }
    RowCard("Advanced", { graph.nav.sheet = Sheet(SheetKind.ADVANCED, h.id) }) {
        Text("Rules · ghost gear ${if (req.ghost) "on" else "off"}", style = zen(12.sp))
    }
}

fun focusRating(goal: Goal) = when (goal) {
    Goal.EHP -> Rating.EHP; Goal.SPEED -> Rating.SPD; Goal.CP -> Rating.CP; Goal.GEAR_SCORE -> Rating.GS; else -> Rating.DMG
}

fun ratingText(r: Rating, v: Double) = when (r) {
    Rating.CR, Rating.CD, Rating.EFF, Rating.RES -> "${fmtNum(Math.floor(v))}%"
    Rating.GS -> fmtNum(Math.round(v * 10) / 10.0)
    else -> fmtInt(v)
}

@Composable
@OptIn(LinkStyle::class)
fun ResultsScreen(graph: AppGraph) {
    graph.rev.value
    val s = graph.optimize.search ?: run { OptimizeScreen(graph); return }
    val h = graph.heroes.hero(s.heroId) ?: return
    val req = graph.heroes.request(h.id)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.running) { while (s.running) { now = System.currentTimeMillis(); delay(100) } }
    val r = graph.settings.showRating
    val byMatch = req.goal == Goal.TARGETS && graph.optimize.showMatch
    Frame(graph, Screen.Optimize, primary = if (s.builds.isEmpty()) null else Action("Compare #1") { graph.nav.go(Screen.Compare(0)) }) {
        BackToPrevious()
        if (s.historyAt != 0L) HistoryNote(s.historyAt)
        ScreenTitle("Best 50") { ScanChip(graph.scan.scannedLabel(), graph.scan.phase != ScanPhase.IDLE) { if (graph.scan.phase != ScanPhase.IDLE) graph.nav.go(Screen.Capture) else graph.nav.go(Screen.ScanData) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Face(graph.heroes.face(h), 26.dp, ringWidth = 2.dp)
            Text("${graph.heroes.heroName(h)} · ${req.goal.label}", style = zen(13.sp).copy(lineHeight = 16.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        StaleBox(graph)
        if (s.running) {
            Card(shadow = null, radius = 14.dp, border = 2.5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Searching · ${"%.1f".format(java.util.Locale.US, (now - s.startedAt) / 1000.0)} s", style = zen(12.sp), modifier = Modifier.weight(1f))
                    LinkText("Cancel", color = C.deepPink) { graph.optimize.cancelSearch() }
                }
                VSpace(6.dp)
                val frac = ((now - s.startedAt).toFloat() / s.limitMs).coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(99.dp)).border(2.dp, C.edge, RoundedCornerShape(99.dp))) {
                    Box(Modifier.fillMaxWidth(frac).height(9.dp).stripes(C.pink, C.lightPink, shape = RoundedCornerShape(99.dp)))
                }
                VSpace(6.dp)
                Text("Best so far is shown. Stops at ${s.limitMs / 1000} s.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SHOW", style = zen(12.sp, color = C.muted).copy(letterSpacing = 0.7.sp))
                val targets = req.goal == Goal.TARGETS
                val main = if (targets) listOf(Rating.DMG, Rating.SPD, Rating.EHP) else listOf(Rating.DMG, Rating.SPD, Rating.CP, Rating.EHP)
                if (targets) Pill("Match", selected = byMatch, onClick = { graph.optimize.showMatch = true })
                main.forEach { x -> Pill(when (x) { Rating.DMG -> "Damage"; Rating.SPD -> "Speed"; else -> x.short }, selected = !byMatch && r == x,
                    onClick = { graph.settings.showRating = x; graph.optimize.showMatch = false; graph.store.saveNow() }) }
                Pill(if (byMatch || r in main) "+${Rating.entries.size - main.size}" else r.short, selected = !byMatch && r !in main,
                    onClick = { graph.optimize.showMatch = false; graph.nav.sheet = Sheet(SheetKind.SHOW, h.id) })
            }
        }
        // history and other finished lists without a ghost run have nothing to report there
        if (req.ghost && (s.running || s.ghostRunning || s.ghostSearched)) GhostBand(graph, s, r, if (byMatch) h.id else null)
        ModsLine(graph, s, req)
        val cur = s.current
        if (!s.running && s.builds.isEmpty()) {
            Card(shadow = null, radius = 14.dp, border = 2.dp) {
                Text(when {
                    s.cancelled -> "Search cancelled before any build was found."
                    s.timedOut -> "No build found in ${s.limitMs / 1000} s."
                    else -> "No build meets these rules."
                }, style = dela(14.sp))
                VSpace(4.dp)
                val bl = s.blockers
                Text(when {
                    s.cancelled -> "Search again to finish it."
                    s.checkingBlockers -> "Checking which rule to loosen…"
                    bl.isNullOrEmpty() -> "Loosening any single rule isn't enough. Loosen several: minimums, sets, mains or enhance level."
                    else -> "Loosen any one of these and builds appear: " + bl.joinToString(" · ")
                }, style = zen(12.sp, FontWeight.Medium, C.muted), minLines = 2)
                if (s.timedOut && !s.cancelled && s.limitMs < graph.optimize.longLimitMs) {
                    VSpace(6.dp)
                    Text("It stopped before checking every combination, so a build may still exist. Mods make the search slower.",
                        style = zen(12.sp, FontWeight.Medium, C.muted))
                    VSpace(4.dp)
                    LinkText("Search for ${graph.optimize.longLimitMs / 1000} s") { graph.optimize.startSearch(graph.optimize.longLimitMs) }
                }
            }
        }
        s.builds.forEachIndexed { i, b -> ResultCard(graph, i, b, cur, r, req, if (byMatch) h.id else null) }
        if (s.running) for (i in s.builds.size until 4) SkeletonCard(i)
        if (!s.running && s.builds.isNotEmpty()) {
            Text(when {
                s.timedOut -> "Stopped at ${s.limitMs / 1000} s: these are the best builds found so far."
                s.cancelled -> "Cancelled: these are the best builds found so far."
                else -> "Complete: no other build of your gear beats #${s.builds.size} under these rules."
            }, style = zen(11.sp, FontWeight.Medium, C.muted))
        }
    }
}

/** A box scanned more than a day ago gives stale results; say so where results are made. */
@Composable
@OptIn(LinkStyle::class)
fun StaleBox(graph: AppGraph) {
    val age = if (graph.scan.lastScan > 0) (System.currentTimeMillis() - graph.scan.lastScan) / 3_600_000 else return
    if (age < 24) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hatch().border(2.dp, C.edge, RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Box is ${if (age < 48) "a day" else "${age / 24} days"} old · new gear isn't counted", style = zen(12.sp),
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinkText("Scan now") { graph.nav.go(Screen.ScanData) }
    }
}

/** Says whether substat mods were part of this search, so a list without 改 is not a mystery. */
@Composable
@OptIn(LinkStyle::class)
private fun ModsLine(graph: AppGraph, s: e7.gearscan.features.optimize.SearchState, req: Request) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("改", style = dela(12.sp, if (req.mods) C.deepPink else C.muted))
        val top = s.builds.take(10)
        Text(when {
            !req.mods -> "Substat mods are off"
            s.running -> "Mods on · searching takes longer"
            top.isEmpty() -> "Mods on"
            else -> top.count { b -> b.items.any { it.mod != null } }.let { n -> if (n == 0) "Mods on · no mod beats these builds" else "Mods on · $n of the top ${top.size} use a mod" }
        }, style = zen(12.sp, color = if (req.mods) C.deepPink else C.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!req.mods && !s.running) LinkText("Turn on") { req.mods = true; graph.rev.bump(); graph.store.saveNow(); graph.optimize.startSearch() }
    }
}

@Composable
private fun GhostBand(graph: AppGraph, s: e7.gearscan.features.optimize.SearchState, r: Rating, matchHero: Long? = null) {
    val g = s.ghost
    val best = s.builds.firstOrNull()
    DashedBox(Modifier.fillMaxWidth().heightIn(min = 70.dp), color = C.blue, bg = C.ghostBg, radius = 14.dp, width = 2.5.dp,
        onClick = if (g != null) ({ graph.nav.go(Screen.GhostBuild) }) else null,
        padding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostTag()
            val none = !s.running && best == null
            Text(when {
                g != null && none -> "1 ghost ${g.ghost!!.slot.label.lowercase()} makes a build possible"
                g != null -> "Best with 1 ghost ${g.ghost!!.slot.label.lowercase()}"
                s.running -> "Ghost search runs after the main search"
                s.ghostRunning && none -> "Looking for one ghost piece that meets the rules…"
                s.ghostRunning -> "Looking for the best ghost piece…"
                none -> "Even one ghost piece can't meet these rules"
                else -> "No single ghost piece beats #1"
            }, style = zen(12.sp, color = C.blue), modifier = Modifier.weight(1f))
            ChevronSlot(g != null, C.blue)
        }
        VSpace(5.dp)
        if (g != null && best != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val spd = g.final[Stat.SPD] - best.final[Stat.SPD]
            val gm = matchHero?.let { graph.heroes.matchOf(it, g)?.pct }; val bm = matchHero?.let { graph.heroes.matchOf(it, best)?.pct }
            if (gm != null && bm != null) {
                Text((if (gm >= bm) "+" else "−") + "${Math.abs(gm - bm)}%", style = dela(18.sp, C.blue))
                Text("match over #1" + if (Math.abs(spd) >= 1) ", ${fmtSigned(spd)} spd" else "", style = zen(12.sp, color = C.blue),
                    modifier = Modifier.padding(bottom = 2.dp))
            } else {
                val gain = g.rating(r) - best.rating(r)
                Text(fmtSigned(gain), style = dela(18.sp, C.blue))
                Text("${r.short.lowercase()}${if (r != Rating.SPD && Math.abs(spd) >= 1) ", ${fmtSigned(spd)} spd" else ""} over #1", style = zen(12.sp, color = C.blue),
                    modifier = Modifier.padding(bottom = 2.dp))
            }
        } else if (g != null) Text("Meets every rule · tap to see it", style = zen(12.sp, color = C.blue))
        else if (s.running || s.ghostRunning) Skeleton(Modifier.width(160.dp).height(10.dp))
        else Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ResultCard(graph: AppGraph, i: Int, b: Build, cur: Build?, r: Rating, req: Request, matchHero: Long? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.hardShadow(C.pink, 4.dp, 4.dp, 14.dp) else Modifier)
        .clip(shape).background(C.card).then(if (i == 0) Modifier.halftone() else Modifier).border(2.5.dp, C.edge, shape).tap { graph.nav.go(Screen.Compare(i)) }
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RankChip(i + 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = matchHero?.let { graph.heroes.matchOf(it, b)?.pct }
                // fixed slots: switching Match/Damage (or a longer number) must not push "from others" sideways
                val (main, delta, up) = if (m != null) {
                    // targets goal: the number people compare is how close the build gets, as on Heroes and hero detail
                    val was = cur?.let { c -> graph.heroes.matchOf(matchHero, c)?.pct }
                    Triple("$m%", was?.let { (if (m >= it) "+" else "−") + "${Math.abs(m - it)}%" }, was == null || m >= was)
                } else {
                    val d = cur?.let { b.rating(r) - it.rating(r) }
                    Triple(ratingText(r, b.rating(r)), d?.let { if (r in listOf(Rating.GS)) (if (it >= 0) "+" else "−") + fmtNum(Math.abs(it)) else fmtSigned(it) }, (d ?: 0.0) >= 0)
                }
                FitText(main, dela(18.sp), Modifier.width(78.dp))
                FitText(delta ?: "", zen(12.sp, color = if (up) C.blue else C.red), Modifier.width(52.dp).padding(bottom = 2.dp))
                val mods = b.items.count { it.mod != null }
                if (b.fromOthers > 0 || mods > 0) Text(buildAnnotatedString {
                    if (b.fromOthers > 0) append("${b.fromOthers} from others")
                    if (b.fromOthers > 0 && mods > 0) append(" · ")
                    if (mods > 0) withStyle(SpanStyle(color = C.deepPink)) { append("改 $mods") }
                }, style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp).weight(1f, fill = false))
            }
            StatGrid(req) { b.final[it] }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Calc.setCounts(b.items, graph.content.content).forEach { (sd, _) -> SetIcon(sd.icon, 22.dp) }
        }
    }
}

@Composable
private fun SkeletonCard(i: Int) {
    Card(shadow = null, radius = 14.dp, border = 2.5.dp, bg = C.card.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FitText("#${i + 1}", dela(12.sp, C.faint), Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Skeleton(Modifier.width(90.dp).height(14.dp))
                Skeleton(Modifier.width(130.dp).height(8.dp))
            }
            Skeleton(Modifier.width(44.dp).height(22.dp))
        }
    }
}

@Composable
@OptIn(LinkStyle::class)
fun CompareScreen(graph: AppGraph, index: Int, poolHero: Long?, review: Boolean = false) {
    graph.rev.value
    val s = graph.optimize.search
    val heroId = poolHero ?: s?.heroId ?: return
    val b = (if (poolHero != null) graph.pool.state?.builds?.get(poolHero) else s?.builds?.getOrNull(index)) ?: return
    val cur = if (poolHero != null) graph.heroes.currentBuild(heroId) else s?.current
    val teamHero = poolHero
    val req = graph.heroes.request(heroId)
    val heroName = graph.heroes.hero(heroId)?.let(graph.heroes::heroName) ?: "Build"
    val queue = if (review) graph.pool.reviewQueue() else emptyList()
    val at = queue.indexOf(heroId)
    val readOnly = graph.pool.state?.fromHistory == true && poolHero != null || graph.optimize.search?.historyAt?.let { it != 0L } == true && poolHero == null
    val prev = queue.getOrNull(at - 1)
    val next = queue.drop(at + 1).firstOrNull { it !in (graph.pool.state?.reviewed ?: emptySet()) }
    val startedAt = graph.pool.state?.startedAt?.takeIf { poolHero != null } ?: graph.optimize.search?.startedAt ?: 0
    Frame(null, null,
        primary = if (review) {
            Action(next?.let { "Next · ${graph.heroes.hero(it)?.let(graph.heroes::heroName)} ›" } ?: "Done", short = next?.let { "Next ›" }) {
                graph.pool.markReviewed(heroId)
                if (next != null) graph.nav.replace(Screen.PoolReview(next)) else graph.nav.back()
            }
        } else Action("Show over game") {
            if (!graph.overGame.show(heroId, b, if (teamHero != null) heroName else "$heroName #${index + 1}",
                    if (readOnly) graph.history.flag(startedAt) else null)) graph.nav.go(Screen.OverlayIntro)
        },
        secondary = if (review) {
            Secondary("‹ Previous", Span.Fixed(118.dp), enabled = prev != null, before = true) { prev?.let { graph.nav.replace(Screen.PoolReview(it)) } }
        } else if (!readOnly) {
            Secondary("Equip in app") { graph.heroes.recordBuild(heroId, b) }
        } else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BackToPrevious(Modifier.weight(1f))
            if (review) {
                if (heroId in graph.pool.state!!.reviewed) { Stamp("済", "reviewed", C.blue); HSpace(8.dp) }
                Text("${at + 1} of ${queue.size}", style = zen(12.sp, color = C.muted))
            } else Calc.setCounts(b.items, graph.content.content).forEach { (sd, _) -> SetIcon(sd.icon, 22.dp) }
        }
        if (readOnly) HistoryNote(graph.pool.state?.startedAt?.takeIf { poolHero != null } ?: graph.optimize.search?.startedAt ?: 0)
        // reviewing a run: change what this hero aims for, then re-run from Pool done
        else if (poolHero != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LinkText("Edit targets") { graph.nav.sheet = Sheet(SheetKind.TARGETS, heroId) }
        }
        if (teamHero != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            graph.heroes.hero(heroId)?.let { Face(graph.heroes.face(it), 44.dp) }
            FitText(graph.heroes.hero(heroId)?.let(graph.heroes::heroName) ?: "Build", TitleStyle, Modifier.weight(1f))
        } else ScreenTitle("Build #${index + 1}")
        Card(padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            CompareRow(null, "STAT", "NOW", "NEW", "Δ", header = true)
            for (st in Stat.entries) {
                val n = cur?.final?.get(st) ?: 0.0
                val v = b.final[st]
                CompareRow(st, st.label.replace("Crit dmg", "Crit damage"), fmtNum(Math.floor(n)), fmtNum(Math.floor(v)), Math.floor(v) - Math.floor(n))
            }
            run {
                val was = cur?.let { graph.heroes.matchOf(heroId, it)?.pct }; val now = graph.heroes.matchOf(heroId, b)?.pct
                if (now != null) CompareRow(null, "Match", was?.let { "$it%" } ?: "—", "$now%", (now - (was ?: now)).toDouble())
            }
            val fr = focusRating(req.goal).let { if (it == Rating.SPD) Rating.DMG else it }
            CompareRow(null, fr.label, fmtInt(cur?.rating(fr) ?: 0.0), fmtInt(b.rating(fr)), b.rating(fr) - (cur?.rating(fr) ?: 0.0))
        }
        if (poolHero != null) TakenByOthers(graph, poolHero)
        SectionTitle("Gear", trailing = if (b.fromOthers > 0) "${b.fromOthers} from others" else "all own")
        GearGrid(graph, b.items, heroId, req.reforged, openHolder = poolHero?.let { { who: Long -> graph.pool.open(who) } })
    }
}

/** Pieces the hero wears now that another hero of the pool run took: why its new build can be worse. */
@Composable
private fun TakenByOthers(graph: AppGraph, heroId: Long) {
    val p = graph.pool.state ?: return
    val worn = graph.scan.box?.gearOf(heroId).orEmpty()
    val takenBy = p.builds.filterKeys { it != heroId }.flatMap { (who, b) -> b?.items.orEmpty().map { it.id to who } }.toMap()
    val lost = Slot.entries.mapNotNull { s -> worn[s]?.let { it to (takenBy[it.id] ?: return@mapNotNull null) } }
    if (lost.isEmpty()) return
    SectionTitle("Taken by other heroes", trailing = "${lost.size}")
    Text("Pieces this hero wears now that went to another hero, because the pool gained more than this hero lost. Tap a hero to see its build.",
        style = zen(11.sp, FontWeight.Medium, C.muted))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lost.forEach { (item, who) ->
            val shape = RoundedCornerShape(10.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(C.card).border(2.dp, C.edge, shape).padding(7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearTile(item, 40.dp, setIcon = graph.content.content.set(item.set)?.icon)
                Column(Modifier.weight(1f)) {
                    Text(item.slot.label, style = zen(12.sp, FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatStack(item, reforged = graph.heroes.request(heroId).reforged, size = 10)
                }
                graph.heroes.hero(who)?.let { h ->
                    Column(Modifier.width(72.dp).tap { graph.pool.open(who) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Face(graph.heroes.face(h), 30.dp, ringWidth = 2.dp)
                        Text("to ${graph.heroes.heroName(h)}", style = zen(10.sp, color = C.deepPink).copy(lineHeight = 12.sp), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareRow(stat: Stat?, label: String, now: String, new: String, delta: Any, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp)) { if (stat != null) StatIcon(stat, 13.dp) }
        val hs = zen(10.sp, color = C.muted)
        FitText(label, if (header) hs.copy(letterSpacing = 0.6.sp) else zen(13.sp), Modifier.weight(1f), short = stat?.short)
        FitText(now, if (header) hs else zen(13.sp, color = C.muted), Modifier.width(54.dp), textAlign = TextAlign.End)
        FitText(new, if (header) hs else dela(13.sp), Modifier.width(62.dp), textAlign = TextAlign.End)
        val (dt, dc) = when (delta) {
            is String -> delta to C.muted
            is Double -> (if (Math.abs(delta) < 0.5) "0" else fmtSigned(delta)) to (if (delta >= 0) C.blue else C.red)
            else -> "" to C.muted
        }
        FitText(dt, if (header) hs else zen(13.sp, color = dc), Modifier.width(56.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun GearGrid(graph: AppGraph, items: List<Item>, heroId: Long, reforged: Boolean, openHolder: ((Long) -> Unit)? = null) {
    CardGrid(Slot.entries.map { s -> items.firstOrNull { it.slot == s } }) { it, cell -> GearCard(graph, it, heroId, reforged, cell, openHolder) }
}

@Composable
private fun GearCard(graph: AppGraph, it: Item?, heroId: Long, reforged: Boolean, modifier: Modifier, openHolder: ((Long) -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.clip(shape).background(if (it?.ghost == true) C.ghostBg else C.card)
        .then(if (it?.ghost == true) Modifier.dashedBorder(C.blue, 2.dp, 10.dp) else Modifier.border(2.dp, C.edge, shape))
        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // the face on the tile says whose piece it is; a tap opens the build of the hero it comes from
        val holder = it?.equippedOn?.takeIf { h -> h != heroId && !it.ghost }
        val link = holder?.takeIf { h -> openHolder != null && graph.pool.state?.builds?.get(h) != null }
        GearTile(it, 56.dp, setIcon = it?.let { i -> graph.content.content.set(i.set)?.icon }, onClick = link?.let { h -> { openHolder!!(h) } })
        Column(Modifier.weight(1f)) {
            if (it == null) { Text("Empty", style = zen(12.sp, color = C.muted)); return@Column }
            StatStack(it, reforged = reforged, size = 11, fit = true)
            it.mod?.let { m -> Text("Mod ${m.from.short} ${m.from.format(m.fromValue)} → ${m.to.short}", style = zen(10.sp, color = C.deepPink), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun GhostBuildScreen(graph: AppGraph) {
    val s = graph.optimize.search ?: return
    val g = s.ghost ?: return
    val best = s.builds.firstOrNull() ?: s.current ?: return
    val owned = s.builds.isNotEmpty()
    val gp = g.ghost ?: return
    val req = graph.heroes.request(s.heroId)
    val r = graph.settings.showRating.let { if (it == Rating.SPD) Rating.DMG else it }
    Frame(null, null, primary = Action("Back to results") { graph.nav.back() }, secondary = Secondary("Equip", dashed = true, enabled = false) {}) {
        BackToPrevious()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostTag(); Text("Ghost build", style = TitleStyle)
        }
        Card(shadow = C.blue) {
            Text(if (owned) "Gain over your best owned-only build" else "No owned-only build meets the rules · change from current gear",
                style = zen(11.sp, FontWeight.Bold, C.muted))
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FitText(fmtSigned(g.rating(r) - best.rating(r)), dela(26.sp, C.blue))
                Text(r.label.lowercase(), style = zen(12.sp), modifier = Modifier.padding(bottom = 4.dp))
                val ds = g.final[Stat.SPD] - best.final[Stat.SPD]
                if (Math.abs(ds) >= 1) {
                    FitText(fmtSigned(ds), dela(18.sp, C.blue))
                    Text("speed", style = zen(12.sp), modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        SectionTitle("Gear")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Slot.entries.forEach { sl ->
                val it = g.items.firstOrNull { x -> x.slot == sl }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GearTile(it, 46.dp, setIcon = it?.let { i -> graph.content.content.set(i.set)?.icon })
                }
            }
        }
        DashedBox(Modifier.fillMaxWidth(), color = C.blue, bg = C.ghostBg, radius = 14.dp, width = 2.5.dp, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearTile(gp, 40.dp)
                Column(Modifier.weight(1f)) {
                    val set = graph.content.content.set(gp.set)
                    Text("${set?.name ?: gp.set.removeSuffix("Set")} ${gp.slot.label.lowercase()}", style = dela(15.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SetIcon(set?.icon, 16.dp)
                        Text("${set?.name ?: ""} set · ${gp.mainType.short} ${gp.mainType.format(gp.mainValue)} main", style = zen(12.sp, color = C.blue))
                    }
                }
            }
            VSpace(8.dp)
            Text("NEEDS AT LEAST", style = zen(10.sp, color = C.muted).copy(letterSpacing = 0.6.sp))
            VSpace(4.dp)
            CardGrid(gp.subs, gap = 4.dp) { sub, cell ->
                Row(cell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatIcon(sub.type.stat, 12.dp, C.blue)
                    Text("${sub.type.short} ${sub.type.format(sub.value)}", style = zen(13.sp))
                    Text("${sub.rolls}×", style = zen(10.sp, color = C.muted))
                }
            }
            VSpace(8.dp)
            val q = req.ghostQuality.coerceIn(0, 2)
            val replaced = best.items.firstOrNull { it.slot == gp.slot }
            Text("About ${Chance(gp.odds).text} of Epic ${set(graph, gp)} ${gp.slot.label.lowercase()} with this main and 3 useful starting substats " +
                "beat your current one by +15" + (if (req.reforged) " (level 85, then reforged)" else "") +
                ". This ghost is a ${listOf("typical", "top 25%", "top 5%")[q]} one of those.", style = zen(12.sp, FontWeight.Medium))
            if (replaced != null) {
                VSpace(4.dp)
                Text("Replaces: ${replaced.mainType.short} ${replaced.mainType.format(replaced.mainValue)} · " +
                    replaced.subs.joinToString(" · ") { "${it.type.short} ${it.type.format(it.value)}" }, style = zen(11.sp, FontWeight.Medium, C.muted))
            }
            VSpace(4.dp)
            Text("Simulated with the game's roll ranges: 4 substats, 5 upgrades on random ones. A modification stone can close a small gap on one substat.",
                style = zen(11.sp, FontWeight.Medium, C.muted))
        }
        Text("Ghost pieces can't be equipped.", style = zen(11.sp, FontWeight.Medium, C.muted))
    }
}

private fun set(graph: AppGraph, it: Item) = graph.content.content.set(it.set)?.name ?: it.set
