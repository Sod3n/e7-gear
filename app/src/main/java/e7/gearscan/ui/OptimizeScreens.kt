package e7.gearscan.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.Screen
import e7.gearscan.ScanPhase
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
fun OptimizeScreen(model: AppModel) {
    model.rev
    val box = model.box
    val team = model.team.mapNotNull { model.hero(it) }
    val pool = box != null && model.poolMode
    val history = box != null && model.showHistory
    Frame(model, Screen.Optimize, actions = when {
        history -> null
        pool -> ({ PoolButton(model) })
        team.isEmpty() -> null
        else -> ({
            PinkButton("Search", Modifier.weight(1f),
                enabled = team.all { model.gameHero(it) != null }) {
                model.startSearch()
                if (team.size == 1) model.go(Screen.Results)
            }
        })
    }) {
        ScreenTitle("Optimize", "最適化") { ScanChip(model.scannedLabel(), model.scanPhase != ScanPhase.IDLE) { if (model.scanPhase != ScanPhase.IDLE) model.go(Screen.Capture) else model.go(Screen.ScanData) } }
        CrashCard(model)
        model.appUpdate?.let { u ->
            val p = model.updateProgress
            Card(shadow = C.pink, padding = PaddingValues(0.dp)) {
                ListRow({ model.updateApp() }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), trailing = {
                    Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(if (p != null) "${(p * 100).toInt()}%" else if (u.apk != null) "Update" else "Download", style = zen(12.sp, color = C.deepPink))
                    }
                }) {
                    Text("E7 Gear ${u.version} is out", style = zen(13.sp))
                    Text(model.updateProblem ?: "You have ${model.appVersion}. Your heroes and builds stay.",
                        style = zen(11.sp, FontWeight.Medium, if (model.updateProblem != null) C.deepPink else C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (box != null) Segmented(listOf("One hero", "Whole pool", "History"), if (model.showHistory) 2 else if (model.poolMode) 1 else 0) {
            model.showHistory = it == 2
            if (it < 2) model.poolMode = it == 1
        }
        if (box != null && model.pool?.running == true) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.ink)
            .tap { model.go(Screen.PoolRun) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(8.dp)
            Text("Pool running · ${model.pool!!.builds.size} of ${model.pool!!.ids.size}", style = zen(12.sp, color = C.card), modifier = Modifier.weight(1f))
            Text("Open ›", style = zen(12.sp, color = C.card))
        }
        when {
            box == null -> NoBox(model)
            history -> HistoryList(model)
            pool -> PoolSetup(model)
            team.isEmpty() -> Card {
                Text("Choose a hero to optimize", style = dela(16.sp))
                VSpace(8.dp)
                OutlineButton("Choose hero", height = 40.dp) { model.sheet = Sheet(SheetKind.HERO_PICK, 0) }
            }
            else -> SingleSetup(model, team[0])
        }
    }
}

@Composable
private fun NoBox(model: AppModel) {
    Card {
        Text("No heroes yet", style = dela(17.sp))
        VSpace(4.dp)
        Text("Scan the game once to load your heroes and gear, or import a Fribbels gear.txt.", style = zen(13.sp, FontWeight.Medium, C.muted))
        VSpace(10.dp)
        PinkButton("Scan the game", Modifier.fillMaxWidth()) { model.go(if (model.consent) Screen.ScanData else Screen.ScanIntro) }
        VSpace(8.dp)
        LinkText("Restore a backup or import a gear.txt") { model.go(Screen.ScanData) }
    }
}

/** The artifact icon beside the subtitle line on hero cards. */
@Composable
fun HeroSubtitle(model: AppModel, h: OwnedHero, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ArtifactIcon(model, model.content.artifact(model.config(h.id).artifact ?: model.request(h.id).artifact), 18.dp)
        Text(text, style = zen(12.sp, FontWeight.Medium, C.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

fun heroSubtitle(model: AppModel, h: OwnedHero): String {
    val cfg = model.config(h.id)
    val art = model.content.artifact(cfg.artifact ?: model.request(h.id).artifact)
    return (art?.let { "${it.name} +30" } ?: "Artifact +30") + " · full imprint, always"
}

@Composable
private fun SingleSetup(model: AppModel, h: OwnedHero) {
    val req = model.request(h.id)
    Card(padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Face(model.face(h), 50.dp)
            Column(Modifier.weight(1f)) {
                Text(model.heroName(h), style = dela(17.sp).copy(lineHeight = 21.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                HeroSubtitle(model, h, heroSubtitle(model, h))
            }
            Pill("Change", onClick = { model.sheet = Sheet(SheetKind.HERO_PICK, h.id) })
        }
        if (model.gameHero(h) == null) {
            VSpace(6.dp)
            Text("No game data for this hero yet. It arrives with the next content update.", style = zen(12.sp, color = C.red))
        }
    }
    PresetCard(model, h, req)
    SectionTitle("What it looks for")
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { LooksFor(model, h, req) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Goal: ${req.goal.label}", style = zen(12.sp, color = C.muted))
        LinkText("More goals") { model.sheet = Sheet(SheetKind.GOALS, h.id) }
    }
}

@Composable
internal fun PresetCard(model: AppModel, h: OwnedHero, req: Request) {
    val loading = model.presetLoading[h.code] == true
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().hardShadow(C.pink, 3.dp, 3.dp, 14.dp).clip(shape).background(C.card).border(2.5.dp, C.ink, shape)
        .tap { model.sheet = Sheet(SheetKind.PRESET, h.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
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
                model.presets.containsKey(h.code) && model.presets[h.code] == null -> {
                    Text("No preset", style = zen(13.sp))
                    Text("Community builds couldn't load; set targets yourself", style = zen(11.sp, FontWeight.Medium, C.muted))
                }
                else -> Text("Choose a preset", style = zen(13.sp))
            }
        }
        LinkText("Change") { model.sheet = Sheet(SheetKind.PRESET, h.id) }
    }
}

@Composable
fun SetsSummary(model: AppModel, req: Request) {
    SetChoiceSummary(model, e7.gearscan.data.SetChoice.from(req.setAlts, model.content), 20.dp)
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
internal fun LooksFor(model: AppModel, h: OwnedHero, req: Request) {
    RowCard("Artifact", { model.sheet = Sheet(SheetKind.ARTIFACT, h.id) }) {
        val art = model.content.artifact(model.config(h.id).artifact ?: req.artifact)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtifactIcon(model, art, 20.dp)
            Text(art?.name ?: "None chosen", style = zen(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    RowCard("Sets", { model.sheet = Sheet(SheetKind.SETS, h.id) }) { SetsSummary(model, req) }
    RowCard("Mains", { model.sheet = Sheet(SheetKind.MAINS, h.id) }) {
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
    RowCard("Targets", { model.sheet = Sheet(SheetKind.TARGETS, h.id) }) {
        val set = Stat.entries.filter { !req.targets[it.ordinal].isNaN() }
        if (set.isEmpty()) Text(if (req.goal == Goal.TARGETS) "None · ranks by gear score" else "None", style = zen(12.sp))
        // every target in a fixed stat order (the row wraps), so a weight change never adds, drops or reorders one
        set.forEach { s -> StatValue(s, fmtK(req.targets[s.ordinal])) }
    }
    val cur = model.currentBuild(h.id)
    if (cur != null) RowCard("Now", { model.sheet = Sheet(SheetKind.TARGETS, h.id) }) { StatGrid(req) { cur.final[it] } }
    RowCard("Advanced", { model.sheet = Sheet(SheetKind.ADVANCED, h.id) }) {
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
fun ResultsScreen(model: AppModel) {
    model.rev
    val s = model.search ?: run { OptimizeScreen(model); return }
    val h = model.hero(s.heroId) ?: return
    val req = model.request(h.id)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.running) { while (s.running) { now = System.currentTimeMillis(); delay(100) } }
    val r = model.showRating
    val byMatch = req.goal == Goal.TARGETS && model.showMatch
    Frame(model, Screen.Optimize, actions = if (s.builds.isEmpty()) null else ({
        PinkButton("Compare #1", Modifier.weight(1f)) { model.go(Screen.Compare(0)) }
    })) {
        BackToPrevious(model)
        if (s.historyAt != 0L) HistoryNote(s.historyAt)
        ScreenTitle("Best 50") { ScanChip(model.scannedLabel(), model.scanPhase != ScanPhase.IDLE) { if (model.scanPhase != ScanPhase.IDLE) model.go(Screen.Capture) else model.go(Screen.ScanData) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Face(model.face(h), 26.dp, ringWidth = 2.dp)
            Text("${model.heroName(h)} · ${req.goal.label}", style = zen(13.sp).copy(lineHeight = 16.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        StaleBox(model)
        if (s.running) {
            Card(shadow = null, radius = 14.dp, border = 2.5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Searching · ${"%.1f".format(java.util.Locale.US, (now - s.startedAt) / 1000.0)} s", style = zen(12.sp), modifier = Modifier.weight(1f))
                    LinkText("Cancel", color = C.deepPink) { model.cancelSearch() }
                }
                VSpace(6.dp)
                val frac = ((now - s.startedAt).toFloat() / s.limitMs).coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(99.dp)).border(2.dp, C.ink, RoundedCornerShape(99.dp))) {
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
                if (targets) Pill("Match", selected = byMatch, onClick = { model.showMatch = true })
                main.forEach { x -> Pill(when (x) { Rating.DMG -> "Damage"; Rating.SPD -> "Speed"; else -> x.short }, selected = !byMatch && r == x,
                    onClick = { model.showRating = x; model.showMatch = false; model.save() }) }
                Pill(if (byMatch || r in main) "+${Rating.entries.size - main.size}" else r.short, selected = !byMatch && r !in main,
                    onClick = { model.showMatch = false; model.sheet = Sheet(SheetKind.SHOW, h.id) })
            }
        }
        // history and other finished lists without a ghost run have nothing to report there
        if (req.ghost && (s.running || s.ghostRunning || s.ghostSearched)) GhostBand(model, s, r, if (byMatch) h.id else null)
        ModsLine(model, s, req)
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
                if (s.timedOut && !s.cancelled && s.limitMs < model.longLimitMs) {
                    VSpace(6.dp)
                    Text("It stopped before checking every combination, so a build may still exist. Mods make the search slower.",
                        style = zen(12.sp, FontWeight.Medium, C.muted))
                    VSpace(4.dp)
                    LinkText("Search for ${model.longLimitMs / 1000} s") { model.startSearch(model.longLimitMs) }
                }
            }
        }
        s.builds.forEachIndexed { i, b -> ResultCard(model, i, b, cur, r, req, if (byMatch) h.id else null) }
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
fun StaleBox(model: AppModel) {
    val age = if (model.lastScan > 0) (System.currentTimeMillis() - model.lastScan) / 3_600_000 else return
    if (age < 24) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).hatch().border(2.dp, C.ink, RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Box is ${if (age < 48) "a day" else "${age / 24} days"} old · new gear isn't counted", style = zen(12.sp),
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinkText("Scan now") { model.go(Screen.ScanData) }
    }
}

/** Says whether substat mods were part of this search, so a list without 改 is not a mystery. */
@Composable
private fun ModsLine(model: AppModel, s: e7.gearscan.SearchState, req: Request) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("改", style = dela(12.sp, if (req.mods) C.deepPink else C.muted))
        val top = s.builds.take(10)
        Text(when {
            !req.mods -> "Substat mods are off"
            s.running -> "Mods on · searching takes longer"
            top.isEmpty() -> "Mods on"
            else -> top.count { b -> b.items.any { it.mod != null } }.let { n -> if (n == 0) "Mods on · no mod beats these builds" else "Mods on · $n of the top ${top.size} use a mod" }
        }, style = zen(12.sp, color = if (req.mods) C.deepPink else C.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!req.mods && !s.running) LinkText("Turn on") { req.mods = true; model.touch(); model.save(); model.startSearch() }
    }
}

@Composable
private fun GhostBand(model: AppModel, s: e7.gearscan.SearchState, r: Rating, matchHero: Long? = null) {
    val g = s.ghost
    val best = s.builds.firstOrNull()
    DashedBox(Modifier.fillMaxWidth().heightIn(min = 70.dp), color = C.blue, bg = C.ghostBg, radius = 14.dp, width = 2.5.dp,
        onClick = if (g != null) ({ model.go(Screen.GhostBuild) }) else null,
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
            val gm = matchHero?.let { model.matchOf(it, g)?.pct }; val bm = matchHero?.let { model.matchOf(it, best)?.pct }
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
private fun ResultCard(model: AppModel, i: Int, b: Build, cur: Build?, r: Rating, req: Request, matchHero: Long? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.hardShadow(C.pink, 4.dp, 4.dp, 14.dp) else Modifier)
        .clip(shape).background(C.card).then(if (i == 0) Modifier.halftone() else Modifier).border(2.5.dp, C.ink, shape).tap { model.go(Screen.Compare(i)) }
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RankChip(i + 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = matchHero?.let { model.matchOf(it, b)?.pct }
                // fixed slots: switching Match/Damage (or a longer number) must not push "from others" sideways
                val (main, delta, up) = if (m != null) {
                    // targets goal: the number people compare is how close the build gets, as on Heroes and hero detail
                    val was = cur?.let { c -> model.matchOf(matchHero, c)?.pct }
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
            Calc.setCounts(b.items, model.content).forEach { (sd, _) -> SetIcon(sd.icon, 22.dp) }
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
fun CompareScreen(model: AppModel, index: Int, poolHero: Long?, review: Boolean = false) {
    model.rev
    val s = model.search
    val heroId = poolHero ?: s?.heroId ?: return
    val b = (if (poolHero != null) model.pool?.builds?.get(poolHero) else s?.builds?.getOrNull(index)) ?: return
    val cur = if (poolHero != null) model.currentBuild(heroId) else s?.current
    val teamHero = poolHero
    val req = model.request(heroId)
    val heroName = model.hero(heroId)?.let(model::heroName) ?: "Build"
    val queue = if (review) model.reviewQueue() else emptyList()
    val at = queue.indexOf(heroId)
    val readOnly = model.pool?.fromHistory == true && poolHero != null || model.search?.historyAt?.let { it != 0L } == true && poolHero == null
    Frame(null, null, actions = if (review) ({
        // always laid out (dimmed on the first hero) so Next keeps its place
        val prev = queue.getOrNull(at - 1)
        OutlineButton("‹ Previous", Modifier.width(118.dp), enabled = prev != null) { prev?.let { model.replace(Screen.PoolReview(it)) } }
        val next = queue.drop(at + 1).firstOrNull { it !in model.pool!!.reviewed }
        PinkButton(next?.let { "Next · ${model.hero(it)?.let(model::heroName)} ›" } ?: "Done", Modifier.weight(1f), short = next?.let { "Next ›" }) {
            model.markReviewed(heroId)
            if (next != null) model.replace(Screen.PoolReview(next)) else model.back()
        }
    }) else ({
        val at = model.pool?.startedAt?.takeIf { poolHero != null } ?: model.search?.startedAt ?: 0
        PinkButton("Show over game", Modifier.weight(1f)) {
            if (!model.showOverGame(heroId, b, if (teamHero != null) heroName else "$heroName #${index + 1}",
                    if (readOnly) model.historyFlag(at) else null)) model.go(Screen.OverlayIntro)
        }
        // a past run's pieces may have moved; the checklist checks each one in the game, equipping blind here doesn't
        if (!readOnly) OutlineButton("Equip in app") { model.recordBuild(heroId, b) }
    })) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BackToPrevious(model, Modifier.weight(1f))
            if (review) {
                if (heroId in model.pool!!.reviewed) { Stamp("済", "reviewed", C.blue); HSpace(8.dp) }
                Text("${at + 1} of ${queue.size}", style = zen(12.sp, color = C.muted))
            } else Calc.setCounts(b.items, model.content).forEach { (sd, _) -> SetIcon(sd.icon, 22.dp) }
        }
        if (readOnly) HistoryNote(model.pool?.startedAt?.takeIf { poolHero != null } ?: model.search?.startedAt ?: 0)
        // reviewing a run: change what this hero aims for, then re-run from Pool done
        else if (poolHero != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LinkText("Edit targets") { model.sheet = Sheet(SheetKind.TARGETS, heroId) }
        }
        if (teamHero != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            model.hero(heroId)?.let { Face(model.face(it), 44.dp) }
            FitText(model.hero(heroId)?.let(model::heroName) ?: "Build", TitleStyle, Modifier.weight(1f))
        } else ScreenTitle("Build #${index + 1}")
        Card(padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            CompareRow(null, "STAT", "NOW", "NEW", "Δ", header = true)
            for (st in Stat.entries) {
                val n = cur?.final?.get(st) ?: 0.0
                val v = b.final[st]
                CompareRow(st, st.label.replace("Crit dmg", "Crit damage"), fmtNum(Math.floor(n)), fmtNum(Math.floor(v)), Math.floor(v) - Math.floor(n))
            }
            run {
                val was = cur?.let { model.matchOf(heroId, it)?.pct }; val now = model.matchOf(heroId, b)?.pct
                if (now != null) CompareRow(null, "Match", was?.let { "$it%" } ?: "—", "$now%", (now - (was ?: now)).toDouble())
            }
            val fr = focusRating(req.goal).let { if (it == Rating.SPD) Rating.DMG else it }
            CompareRow(null, fr.label, fmtInt(cur?.rating(fr) ?: 0.0), fmtInt(b.rating(fr)), b.rating(fr) - (cur?.rating(fr) ?: 0.0))
        }
        if (poolHero != null) TakenByOthers(model, poolHero)
        SectionTitle("Gear", trailing = if (b.fromOthers > 0) "${b.fromOthers} from others" else "all own")
        GearGrid(model, b.items, heroId, req.reforged, openHolder = poolHero?.let { { who: Long -> model.openPoolHero(who) } })
    }
}

/** Pieces the hero wears now that another hero of the pool run took: why its new build can be worse. */
@Composable
private fun TakenByOthers(model: AppModel, heroId: Long) {
    val p = model.pool ?: return
    val worn = model.box?.gearOf(heroId).orEmpty()
    val takenBy = p.builds.filterKeys { it != heroId }.flatMap { (who, b) -> b?.items.orEmpty().map { it.id to who } }.toMap()
    val lost = Slot.entries.mapNotNull { s -> worn[s]?.let { it to (takenBy[it.id] ?: return@mapNotNull null) } }
    if (lost.isEmpty()) return
    SectionTitle("Taken by other heroes", trailing = "${lost.size}")
    Text("Pieces this hero wears now that went to another hero, because the pool gained more than this hero lost. Tap a hero to see its build.",
        style = zen(11.sp, FontWeight.Medium, C.muted))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lost.forEach { (item, who) ->
            val shape = RoundedCornerShape(10.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(C.card).border(2.dp, C.ink, shape).padding(7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearTile(item, 40.dp, setIcon = model.content.set(item.set)?.icon)
                Column(Modifier.weight(1f)) {
                    Text("${item.slot.label} · ${item.mainType.short} ${item.mainType.format(item.mainValue)}", style = zen(12.sp, FontWeight.Black),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SubGrid(item, model.request(heroId).reforged, 10)
                }
                model.hero(who)?.let { h ->
                    Column(Modifier.width(72.dp).tap { model.openPoolHero(who) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Face(model.face(h), 30.dp, ringWidth = 2.dp)
                        Text("to ${model.heroName(h)}", style = zen(10.sp, color = C.deepPink).copy(lineHeight = 12.sp), maxLines = 2,
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
fun GearGrid(model: AppModel, items: List<Item>, heroId: Long, reforged: Boolean, openHolder: ((Long) -> Unit)? = null) {
    CardGrid(Slot.entries.map { s -> items.firstOrNull { it.slot == s } }) { it, cell -> GearCard(model, it, heroId, reforged, cell, openHolder) }
}

@Composable
private fun GearCard(model: AppModel, it: Item?, heroId: Long, reforged: Boolean, modifier: Modifier, openHolder: ((Long) -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.clip(shape).background(if (it?.ghost == true) C.ghostBg else C.card)
        .then(if (it?.ghost == true) Modifier.dashedBorder(C.blue, 2.dp, 10.dp) else Modifier.border(2.dp, C.ink, shape))
        .padding(7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GearTile(it, 40.dp, setIcon = it?.let { i -> model.content.set(i.set)?.icon })
        Column(Modifier.weight(1f)) {
            if (it == null) { Text("Empty", style = zen(12.sp, color = C.muted)); return@Column }
            val ref = reforged && it.reforgeable
            FitText("${it.mainType.short} ${it.mainType.format(if (ref) Reforge.main(it.mainType, it.mainValue) else it.mainValue)}",
                zen(11.sp, FontWeight.Black))
            run {
                val who = when {
                    it.ghost -> "ghost"
                    it.equippedOn == null -> "free"
                    it.equippedOn == heroId -> "own"
                    else -> model.hero(it.equippedOn!!)?.let(model::heroName) ?: "other"
                }
                val holder = it.equippedOn?.takeIf { h -> h != heroId && !it.ghost }
                val link = holder?.takeIf { h -> openHolder != null && model.pool?.builds?.get(h) != null }
                Text(if (who == "own" || who == "free" || who == "ghost") who else "on $who", style = zen(10.sp, color = if (who == "own" || who == "free") C.muted else C.blue).copy(lineHeight = 12.sp,
                    textDecoration = if (link != null) androidx.compose.ui.text.style.TextDecoration.Underline else null),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.tap(link?.let { h -> { openHolder!!(h) } }))
            }
            VSpace(2.dp)
            SubGrid(it, reforged, 10)
            it.mod?.let { m -> Text("Mod ${m.from.short} ${m.from.format(m.fromValue)} → ${m.to.short}", style = zen(10.sp, color = C.deepPink), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun GhostBuildScreen(model: AppModel) {
    val s = model.search ?: return
    val g = s.ghost ?: return
    val best = s.builds.firstOrNull() ?: s.current ?: return
    val owned = s.builds.isNotEmpty()
    val gp = g.ghost ?: return
    val req = model.request(s.heroId)
    val r = model.showRating.let { if (it == Rating.SPD) Rating.DMG else it }
    Frame(null, null, actions = {
        PinkButton("Back to results", Modifier.weight(1f)) { model.back() }
        OutlineButton("Equip", dashed = true, enabled = false) {}
    }) {
        BackToPrevious(model)
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
                    GearTile(it, 46.dp, setIcon = it?.let { i -> model.content.set(i.set)?.icon })
                    Text(when {
                        it == null -> "—"; it.ghost -> "ghost"; it.equippedOn == s.heroId -> "own"; it.equippedOn == null -> "free"
                        else -> model.hero(it.equippedOn!!)?.let(model::heroName)?.take(7) ?: "other"
                    }, style = zen(10.sp, color = if (it?.ghost == true) C.blue else C.muted))
                }
            }
        }
        DashedBox(Modifier.fillMaxWidth(), color = C.blue, bg = C.ghostBg, radius = 14.dp, width = 2.5.dp, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearTile(gp, 40.dp)
                Column(Modifier.weight(1f)) {
                    val set = model.content.set(gp.set)
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
            val oneIn = maxOf(1, Math.round(1 / gp.odds.coerceAtLeast(1e-4)).toInt())
            val replaced = best.items.firstOrNull { it.slot == gp.slot }
            Text("About 1 in $oneIn Epic ${set(model, gp)} ${gp.slot.label.lowercase()} with this main and 3 useful starting substats " +
                "beats your current one by +15" + (if (req.reforged) " (level 85, then reforged)" else "") +
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

private fun set(model: AppModel, it: Item) = model.content.set(it.set)?.name ?: it.set
