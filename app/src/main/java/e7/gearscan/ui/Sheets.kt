package e7.gearscan.ui

import e7.gearscan.POOL_RULES
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.alpha
import e7.gearscan.data.SetChoice
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.fmtInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import e7.gearscan.AppModel
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import e7.gearscan.calc.Rating
import e7.gearscan.data.Bonus
import e7.gearscan.data.Goal
import e7.gearscan.data.Request
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.data.fmtK
import e7.gearscan.data.fmtNum
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.Mods

@Composable
fun SheetHost(model: AppModel) {
    val sheet = model.sheet ?: return
    BackHandler { model.sheet = null }
    val scope = rememberCoroutineScope()
    var dy by remember(sheet) { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    fun settle(velocity: Float) = scope.launch {
        val close = dy > height * 0.25f || velocity > 1800f
        animate(dy, if (close) height else 0f, animationSpec = if (close) tween(160) else spring()) { v, _ -> dy = v }
        if (close) model.sheet = null
    }
    // content scrolled to its top hands the rest of a downward drag to the sheet, like a standard bottom sheet
    val nested = remember(sheet) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && dy > 0f) {
                    val used = maxOf(available.y, -dy)
                    dy += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    dy += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dy > 0f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }
    val drag = rememberDraggableState { d -> dy = (dy + d).coerceAtLeast(0f) }
    Box(Modifier.fillMaxSize().background(C.scrim.copy(alpha = C.scrim.alpha * (1f - dy / height).coerceIn(0f, 1f)))
        .tap { model.sheet = null }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (sheet.kind in tall) 0.92f else 0.86f)
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .offset { IntOffset(0, dy.roundToInt()) }
            .nestedScroll(nested)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(C.card).tap { }
            .navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { v -> settle(v) })
                .padding(top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                SeigaihaStrip()
                // card-coloured halo so the waves stop short of the handle
                Box(Modifier.width(52.dp).height(13.dp).background(C.card))
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.ink))
            }
            when (sheet.kind) {
                SheetKind.PRESET -> PresetSheet(model, sheet.heroId)
                SheetKind.TARGETS -> TargetsSheet(model, sheet.heroId)
                SheetKind.ADVANCED -> AdvancedSheet(model, sheet.heroId)
                SheetKind.BONUS -> BonusSheet(model, sheet.heroId)
                SheetKind.ARTIFACT -> ArtifactSheet(model, sheet)
                SheetKind.SETS -> SetsSheet(model, sheet.heroId)
                SheetKind.MAINS -> MainsSheet(model, sheet.heroId)
                SheetKind.GOALS -> GoalsSheet(model, sheet.heroId)
                SheetKind.SHOW -> ShowSheet(model)
                SheetKind.HERO_PICK -> HeroPickSheet(model, sheet)
                SheetKind.POOL_HEROES -> PoolHeroesSheet(model)
                SheetKind.POOL_LIST -> PoolListSheet(model)
                SheetKind.POOL_PICK -> PoolPickSheet(model)
                SheetKind.POOL_WORSE -> PoolWorseSheet(model)
                SheetKind.POOL_LOW -> PoolLowSheet(model)
                SheetKind.POOL_TRADES -> PoolTradesSheet(model)
                SheetKind.SCAN_RESTART -> ScanRestartSheet(model)
            }
        }
    }
}

private val tall = setOf(SheetKind.TARGETS, SheetKind.HERO_PICK, SheetKind.ARTIFACT, SheetKind.POOL_PICK, SheetKind.POOL_HEROES, SheetKind.POOL_LIST)

@Composable
internal fun SheetBody(title: String, trailing: @Composable (() -> Unit)? = null, button: String? = null, onButton: (() -> Unit)? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TitleStyle, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
        if (button != null && onButton != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { PinkButton(button, Modifier.fillMaxWidth(), onClick = onButton) }
        }
    }
}

@Composable
private fun PresetSheet(model: AppModel, heroId: Long) {
    val h = model.hero(heroId) ?: return
    var tab by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(0) }
    val tabs = model.presets[h.code]
    val list = tabs?.tab(tab).orEmpty()
    SheetBody("Presets", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Face(model.face(h), 26.dp, ringWidth = 2.dp); Text(model.heroName(h), style = zen(12.sp))
        }
    }, button = list.getOrNull(sel)?.let { "Use ${it.label}" }, onButton = list.getOrNull(sel)?.let { p -> {
        model.applyPreset(heroId, p, tabs!!.names[tab]); model.sheet = null
    } }) {
        Text("Builds other players run, from the Fribbels hero library" +
            (tabs?.let { t -> " (${"%,d".format(java.util.Locale.US, t.total)} builds${t.date?.let { d -> ", newest $d" } ?: ""})" } ?: "") +
            ". % = how close your current gear already is. A preset fills in sets, mains and targets (the dark stats); you can change any of it.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        Segmented(tabs?.names ?: listOf("Most used", "Top ranked", "Fastest"), tab) { tab = it; sel = 0 }
        if (tabs?.names?.getOrNull(tab) == "RTA") Text("The same 8 community builds the RTA helper shows: real players' builds, not averages.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
        when {
            model.presetLoading[h.code] == true -> repeat(3) { PresetSkeleton() }
            tabs == null -> {
                Text("Community builds couldn't load. Check the connection and try again.", style = zen(13.sp, FontWeight.Medium))
                OutlineButton("Try again", height = 40.dp) { model.ensurePresets(heroId, force = true) }
            }
            else -> list.forEachIndexed { i, p ->
                val on = i == sel
                Card(shadow = if (on) C.pink else null, radius = 14.dp, border = 2.5.dp, halftone = on, onClick = { sel = i }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, style = dela(15.sp), modifier = Modifier.weight(1f))
                        Text(if (p.rta) "RTA sample" else "used by ${p.share}%", style = zen(11.sp, color = if (on) C.deepPink else C.muted), softWrap = false)
                    }
                    model.matchFor(heroId, p)?.let { m ->
                        VSpace(2.dp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Your gear matches", style = zen(12.sp))
                            MatchChip(m.pct)
                            m.missing.forEach { st -> SetIcon(st.icon, 18.dp); Text("missing", style = zen(11.sp, FontWeight.Medium, C.red)) }
                        }
                    }
                    VSpace(4.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.sets.entries.sortedByDescending { it.value }.forEach { (k, _) -> SetIcon(model.content.set(k)?.icon, 22.dp) }
                        HSpace(4.dp)
                        val art = model.content.artifact(p.artifact)
                        if (art != null) ArtifactIcon(model, art, 22.dp)
                        Text(art?.name ?: "", style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    VSpace(4.dp)
                    // all eight in fixed cells: dark ones become targets, faint ones the build has but won't chase
                    val asks = model.presetRequest(heroId, p)
                    CardGrid(Stat.entries, columns = 4, gap = 0.dp) { st, cell ->
                        val v = p.median[st.ordinal]
                        val target = asks?.targets?.get(st.ordinal)?.isNaN() == false
                        Box(cell) { StatValue(st, if (v.isNaN() || v <= 0) "—" else fmtK(v), if (target) C.ink else C.faint, 11) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSkeleton() {
    Card(shadow = null, radius = 14.dp, border = 2.5.dp) {
        Skeleton(Modifier.width(120.dp).height(12.dp)); VSpace(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Skeleton(Modifier.size(22.dp)); Skeleton(Modifier.size(22.dp)) }
        VSpace(8.dp); Skeleton(Modifier.width(190.dp).height(8.dp))
    }
}

@Composable
private fun NumBox(value: Double, pink: Boolean, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value.isNaN()) "" else fmtNum(value)) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.height(30.dp).clip(shape).background(C.card).border(2.dp, if (pink) C.pink else C.ink, shape).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center) {
        if (text.isEmpty()) Text("—", style = zen(12.sp, color = C.faint))
        BasicTextField(text, { t ->
            text = t.filter { it.isDigit() || it == '.' }.take(6)
            onChange(text.toDoubleOrNull() ?: Double.NaN)
        }, textStyle = dela(11.sp).copy(textAlign = TextAlign.Center), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TargetsSheet(model: AppModel, heroId: Long) {
    model.rev
    val req = model.request(heroId)
    val cur = remember(heroId) { model.currentBuild(heroId) }
    var open by remember { mutableStateOf<Stat?>(null) }
    var extra by remember { mutableStateOf(emptySet<Stat>()) }
    val focus = LocalFocusManager.current
    fun set(s: Stat) = s.ordinal.let { req.weights[it] > 0 || !req.mins[it].isNaN() || !req.targets[it].isNaN() || !req.maxs[it].isNaN() }
    val usual = listOf(Stat.SPD, Stat.CR, Stat.CD, Stat.ATK, Stat.HP, Stat.DEF, Stat.RES)
    val sorted = Stat.entries.filter { set(it) }.sortedByDescending { req.weights[it.ordinal] } +
        usual.filter { !set(it) } + extra.filter { !set(it) && it !in usual }
    // order fixed while the sheet is open: editing a weight must not make rows jump
    val first = remember(heroId) { sorted }
    val rows = first + sorted.filter { it !in first }
    SheetBody("Stat targets", trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            model.presetReset(heroId)?.let { reset -> LinkText("Reset to preset") { reset(); open = null } }
            LinkText("Clear") {
                req.targets.fill(Double.NaN); req.mins.fill(Double.NaN); req.maxs.fill(Double.NaN); req.weights.fill(0); req.fromPreset.fill(false)
                model.touch(); model.save()
            }
        }
    }, button = open?.let { "Done with ${it.label}" } ?: "Done", onButton = {
        // with a stat open the button finishes that stat; the sheet closes only from the list
        if (open != null) { focus.clearFocus(); open = null } else { model.save(); model.sheet = null }
    }) {
        Text("Tap a stat to edit. Min and max drop builds outside them; target is where to land; weight decides which miss hurts most. " +
            "Pink targets come from the preset, blue ones you changed.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        Column {
            val hs = zen(10.sp, color = C.muted).copy(letterSpacing = 0.5.sp)
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.width(16.dp))
                Text("STAT", style = hs, modifier = Modifier.weight(1f))
                FitText("NOW", hs, Modifier.width(40.dp), textAlign = TextAlign.End)
                FitText("MIN", hs, Modifier.width(52.dp), textAlign = TextAlign.Center)
                FitText("TARGET", hs, Modifier.width(58.dp), textAlign = TextAlign.Center)
                FitText("WT", hs, Modifier.width(44.dp), textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(C.ink))
            for (st in rows) {
                val now = cur?.final?.get(st)
                if (st == open) TargetEditor(model, req, st, now) { open = null }
                else TargetRow(req, st, now) { open = st }
            }
        }
        val hidden = Stat.entries.filter { it !in rows }
        if (hidden.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            hidden.forEach { st -> Text("+ ${st.label}", style = zen(12.sp, color = C.muted), modifier = Modifier.tap { extra = extra + st; open = st }) }
        }
    }
}

@Composable
internal fun TargetRow(req: Request, st: Stat, now: Double?, onTap: () -> Unit) {
    val i = st.ordinal
    Row(Modifier.fillMaxWidth().height(42.dp).tap(onTap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatIcon(st, 15.dp)
        Column(Modifier.weight(1f)) {
            Text(st.label, style = zen(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val cap = req.maxs[i].takeIf { !it.isNaN() } ?: if (st == Stat.CR && req.capCrit) e7.gearscan.opt.CRIT_CAP else null
            if (cap != null) Text("max ${fmtInt(cap)}", style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FitText(now?.let { fmtNum(Math.floor(it)) } ?: "—", zen(12.sp, color = C.muted), Modifier.width(40.dp), textAlign = TextAlign.End)
        ValueChip(req.mins[i], zen(12.sp), Modifier.width(52.dp), C.paper)
        ValueChip(req.targets[i], dela(12.sp), Modifier.width(58.dp), if (req.fromPreset[i]) C.lightPink else C.ghostBg)
        Row(Modifier.width(44.dp), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
            for (w in 1..3) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (w <= req.weights[i]) C.pink else Color.Transparent)
                .border(2.dp, C.ink, RoundedCornerShape(3.dp)))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
}

@Composable
private fun ValueChip(v: Double, style: TextStyle, modifier: Modifier, bg: Color = C.lightPink) {
    if (v.isNaN()) FitText("—", style.copy(color = C.muted), modifier, textAlign = TextAlign.Center)
    else FitText(fmtInt(v), style, modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(vertical = 3.dp), textAlign = TextAlign.Center)
}

@Composable
private fun TargetEditor(model: AppModel, req: Request, st: Stat, now: Double?, onClose: () -> Unit) {
    val i = st.ordinal
    val step = when (st) { Stat.ATK -> 50.0; Stat.DEF -> 25.0; Stat.HP -> 250.0; else -> 1.0 }
    val start = now?.let { Math.round(it / step) * step } ?: 0.0
    Column(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).hatch().border(2.5.dp, C.ink, RoundedCornerShape(14.dp))
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().tap(onClose), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatIcon(st, 16.dp)
            Text(st.label, style = zen(14.sp), modifier = Modifier.weight(1f))
            if (now != null) Text("now ${fmtNum(Math.floor(now))}", style = zen(12.sp, color = C.muted))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("MIN", req.mins[i], step, start, Modifier.weight(1f)) { req.mins[i] = it; model.touch() }
            Stepper("TARGET", req.targets[i], step, start, Modifier.weight(1f)) {
                req.targets[i] = it; req.fromPreset[i] = false
                if (!it.isNaN() && req.weights[i] == 0) req.weights[i] = 1
                model.touch()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Stepper("MAX", req.maxs[i], step, start, Modifier.weight(1f)) { req.maxs[i] = it; model.touch() }
            Text(if (st == Stat.CR && req.capCrit && req.maxs[i].isNaN()) "105 by default: over 100 is wasted"
                else "Builds above it are left out", style = zen(11.sp, FontWeight.Medium, C.muted), modifier = Modifier.weight(1f).padding(bottom = 6.dp))
        }
        Text("WEIGHT · how much a miss hurts", style = zen(11.sp, color = C.muted))
        WeightBar(req.weights[i]) { req.weights[i] = it; model.touch() }
    }
}

/** − value + box; empty shows "—", the first + starts from the current stat. */
@Composable
private fun Stepper(label: String, value: Double, step: Double, start: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(if (value.isNaN()) "" else fmtNum(value)) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = zen(11.sp, color = C.muted))
        Row(Modifier.fillMaxWidth().height(44.dp).clip(shape).background(C.card).border(2.5.dp, C.ink, shape),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp).fillMaxHeight().tap { if (!value.isNaN()) onChange((value - step).coerceAtLeast(0.0)) },
                contentAlignment = Alignment.Center) { Text("−", style = dela(16.sp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (text.isEmpty()) Text("—", style = dela(15.sp, C.faint))
                BasicTextField(text, { t ->
                    text = t.filter { it.isDigit() || it == '.' }.take(6)
                    onChange(text.toDoubleOrNull() ?: Double.NaN)
                }, textStyle = dela(15.sp).copy(textAlign = TextAlign.Center), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.width(36.dp).fillMaxHeight().tap { onChange(if (value.isNaN()) start else value + step) },
                contentAlignment = Alignment.Center) { Text("+", style = dela(16.sp)) }
        }
    }
}

@Composable
private fun AdvancedSheet(model: AppModel, heroId: Long) {
    model.rev
    val pool = heroId == POOL_RULES
    val reqs = when {
        pool -> model.poolHeroes().map { model.request(it.id) }
        heroId == -1L -> model.team.map { model.request(it) }
        else -> listOf(model.request(heroId))
    }
    val req = reqs.firstOrNull() ?: return
    fun edit(f: (Request) -> Unit) { reqs.forEach(f); model.touch(); model.save() }
    // with several heroes a switch reads on only when it is on for all, and a choice shows only when all agree
    fun on(f: (Request) -> Boolean) = reqs.all(f)
    fun <T> pick(options: List<T>, f: (Request) -> T) = reqs.map(f).distinct().singleOrNull()?.let { options.indexOf(it) } ?: -1
    SheetBody(if (pool) "Rules for every hero" else "Advanced", trailing = if (pool) ({ Text("${reqs.size} heroes", style = zen(12.sp, color = C.muted)) }) else null,
        button = "Done", onButton = { model.sheet = null }) {
        if (pool) {
            Text("A change here applies to every hero in the run. A hero's own Advanced can still differ afterwards.",
                style = zen(12.sp, FontWeight.Medium, C.muted))
            Text("Losses", style = zen(13.sp))
            Segmented(e7.gearscan.opt.PoolLosses.entries.map { it.label }, model.poolLosses.ordinal) { i ->
                model.poolLosses = e7.gearscan.opt.PoolLosses.entries[i]; model.touch(); model.save()
            }
            Text(if (model.poolLosses == e7.gearscan.opt.PoolLosses.SAFE) "No hero ends worse than now."
                else "A hero may give up a little when another gains at least twice as much, weighted by tier.",
                style = zen(11.sp, FontWeight.Medium, C.muted), minLines = 2)
            Text("Effort", style = zen(13.sp))
            Segmented(e7.gearscan.opt.PoolEffort.entries.map { it.label }, model.poolEffort.ordinal) { i ->
                model.poolEffort = e7.gearscan.opt.PoolEffort.entries[i]; model.touch(); model.save()
            }
            Text(when (model.poolEffort) {
                e7.gearscan.opt.PoolEffort.TIDY -> "Only moves that clearly pay off. Fewest swaps."
                e7.gearscan.opt.PoolEffort.BALANCED -> "A move must earn about one match point on a top-tier hero."
                e7.gearscan.opt.PoolEffort.REBUILD -> "Small gains are worth a move too. Most swaps."
            }, style = zen(11.sp, FontWeight.Medium, C.muted), minLines = 2)
        }
        Text("Use gear enhanced to at least", style = zen(13.sp))
        val levels = listOf(0, 9, 12, 15)
        Segmented(levels.map { "+$it" }, pick(levels) { it.minEnhance }) { i -> edit { it.minEnhance = levels[i] } }
        ToggleRow("Only take from lower tiers", "Tiers are set in Heroes", on { it.lowerPriorityOnly }) { v -> edit { it.lowerPriorityOnly = v } }
        ToggleRow("Allow locked items", null, on { it.allowLocked }) { v -> edit { it.allowLocked = v } }
        ToggleRow("Keep current gear", "Only fill empty slots", on { it.keepCurrent }) { v -> edit { it.keepCurrent = v } }
        ToggleRow("Use reforged stats", "For level 85 +15 items", on { it.reforged }) { v -> edit { it.reforged = v } }
        Text("Change at most", style = zen(13.sp))
        val caps = listOf(6, 1, 2, 3, 4)
        Segmented(listOf("Any", "1 piece", "2", "3", "4"), pick(caps) { it.maxChanges }) { i -> edit { it.maxChanges = caps[i] } }
        Text("Fewer swaps: builds keep the rest of the hero's current gear. A mod counts as a change.", style = zen(11.sp, FontWeight.Medium, C.muted))
        ToggleRow("Cap crit chance at 105%", "Over 100% is wasted; one roll of slack. A max set in Targets wins", on { it.capCrit }) { v -> edit { it.capCrit = v } }
        ToggleRow("Count Rage's +30% damage", "Only against debuffed targets, so damage leaves it out unless you fight those", on { it.rageDamage }) { v -> edit { it.rageDamage = v } }
        DashedBox(Modifier.fillMaxWidth(), color = C.deepPink, bg = C.lightPink, radius = 14.dp, width = 2.5.dp, padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("改 MOD", bg = C.deepPink, fg = C.card, border = false, size = 10)
                Text("Allow substat mods", style = zen(12.sp), modifier = Modifier.weight(1f))
                Toggle(on { it.mods }) { v -> edit { it.mods = v } }
            }
            VSpace(6.dp)
            Text("One weak substat per +15 item may become a stat this build wants, as a modification stone would. " +
                "Results mark each piece to mod with a pink 改; a blue 改 is a substat already modded in the game, and only that one can be modded again. Searches take a few times longer" +
                if (pool) "; the pool run gives these heroes twice the time." else ".", style = zen(11.sp, FontWeight.Medium, C.muted))
            // always laid out, dimmed while off, so the switch never moves the ghost section below
            Column(Modifier.alpha(if (reqs.any { it.mods }) 1f else 0.4f)) {
                if (heroId >= 0) {
                    VSpace(4.dp)
                    Text("Mods into: " + (model.setup(heroId)?.modWanted()?.joinToString(", ") { it.short } ?: "—"),
                        style = zen(11.sp, color = C.deepPink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                VSpace(10.dp)
                Text("Stone", style = zen(11.sp, color = C.muted)); VSpace(4.dp)
                Segmented(listOf("Lesser", "Greater"), pick(listOf(false, true)) { it.modGreater }) { i -> if (reqs.any { it.mods }) edit { it.modGreater = i == 1 } }
                VSpace(8.dp)
                Text("Value to expect", style = zen(11.sp, color = C.muted)); VSpace(4.dp)
                Segmented(Mods.QUALITY_LABELS, pick(Mods.QUALITY_LABELS.indices.toList()) { it.modQuality }) { i -> if (reqs.any { it.mods }) edit { it.modQuality = i } }
                VSpace(8.dp)
                Text("Only replace substats rolled at most", style = zen(11.sp, color = C.muted)); VSpace(4.dp)
                Segmented(listOf("1 time", "2 times", "3 times"), pick(listOf(1, 2, 3)) { it.modMaxRolls.coerceIn(1, 3) }) { i -> if (reqs.any { it.mods }) edit { it.modMaxRolls = i + 1 } }
            }
        }
        if (heroId >= 0) DashedBox(Modifier.fillMaxWidth(), color = C.blue, bg = C.ghostBg, radius = 14.dp, width = 2.5.dp,
            padding = PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostTag()
                Text("Also show the best build with 1 ghost piece", style = zen(12.sp), modifier = Modifier.weight(1f))
                Toggle(req.ghost) { v -> edit { it.ghost = v } }
            }
            VSpace(10.dp)
            Text("Slots a ghost may fill", style = zen(11.sp, color = C.muted))
            VSpace(6.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Slot.entries.forEach { s ->
                    val on = s in req.ghostSlots
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.slate else C.card)
                        .border(2.dp, if (on) C.ink else C.faint, RoundedCornerShape(10.dp))
                        .tap { edit { r -> r.ghostSlots = if (on) r.ghostSlots - s else r.ghostSlots + s } },
                        contentAlignment = Alignment.Center) {
                        GearIcon(s.icon, 24.dp, if (on) C.card else C.faint)
                    }
                }
            }
            VSpace(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ghost.QUALITY_LABELS.forEachIndexed { i, l -> Pill(l, selected = req.ghostQuality == i, onClick = { edit { it.ghostQuality = i } }) }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = zen(13.sp))
            sub?.let { Text(it, style = zen(11.sp, FontWeight.Medium, C.muted)) }
        }
        Toggle(on, onChange)
    }
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
}

@Composable
private fun BonusSheet(model: AppModel, heroId: Long) {
    model.rev
    val h = model.hero(heroId) ?: return
    val g = model.gameHero(h)
    val cfg = model.config(heroId)
    var more by remember { mutableStateOf(false) }
    SheetBody("Bonus stats", trailing = { Text(model.heroName(h), style = zen(12.sp, color = C.muted)) },
        button = "Save", onButton = { model.save(); model.sheet = null }) {
        Text("Artifact", style = zen(13.sp))
        val art = model.content.artifact(cfg.artifact ?: model.request(heroId).artifact)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(2.5.dp, C.ink, RoundedCornerShape(12.dp))
            .tap { model.sheet = Sheet(SheetKind.ARTIFACT, heroId, back = SheetKind.BONUS) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtifactIcon(model, art, 30.dp)
            Text(art?.name ?: "None (from preset)", style = zen(13.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+30", style = zen(11.sp), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.paper).padding(horizontal = 6.dp, vertical = 2.dp))
            ChevronSlot(true)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.paper).padding(10.dp)) {
            Text("Artifact +30 and full imprint are always applied, so builds compare fairly.", style = zen(11.sp, FontWeight.Medium))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stars", style = zen(12.sp))
                Segmented(listOf("6★", "5★"), if (cfg.stars == 5) 1 else 0) { cfg.stars = if (it == 1) 5 else 6; model.touch() }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Exclusive equip.", style = zen(12.sp))
                val ee = g?.ee?.firstOrNull()
                if (ee == null) Text("None for this hero", style = zen(12.sp, FontWeight.Medium, C.muted), modifier = Modifier.padding(top = 8.dp))
                else {
                    val pct = ee.first != "speed"
                    val v = cfg.eeValue ?: ee.second
                    NumBox(if (pct) v * 100 else v, false, Modifier.fillMaxWidth()) { x ->
                        cfg.eeValue = if (x.isNaN()) null else if (pct) x / 100 else x; model.touch()
                    }
                    Text(eeLabel(ee.first), style = zen(10.sp, FontWeight.Medium, C.muted))
                }
            }
        }
        SectionTitle("Extra bonuses")
        val first = listOf(Bonus.ATK_P, Bonus.HP_P, Bonus.DEF_P, Bonus.SPD, Bonus.CR, Bonus.CD)
        val rest = listOf(Bonus.ATK, Bonus.HP, Bonus.DEF, Bonus.EFF, Bonus.RES, Bonus.FINAL_ATK, Bonus.FINAL_HP, Bonus.FINAL_DEF)
        CardGrid(if (more) first + rest else first, columns = 3) { b, cell ->
            Column(cell.clip(RoundedCornerShape(10.dp)).border(2.dp, C.ink, RoundedCornerShape(10.dp)).padding(8.dp)) {
                FitText(b.label, zen(9.sp, color = C.muted))
                var t by remember(b, cfg.bonus[b.ordinal]) { mutableStateOf(fmtNum(cfg.bonus[b.ordinal])) }
                BasicTextField(t, { s -> t = s.filter { it.isDigit() || it == '.' || it == '-' }.take(6); cfg.bonus[b.ordinal] = t.toDoubleOrNull() ?: 0.0; model.touch() },
                    textStyle = dela(14.sp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
        if (!more) LinkText("More: flat stats, eff, res, final multipliers") { more = true }
    }
}

private fun eeLabel(t: String) = when (t) {
    "att_rate" -> "Attack %"; "max_hp_rate" -> "Health %"; "def_rate" -> "Defense %"; "speed" -> "Speed"
    "cri" -> "Crit chance %"; "acc" -> "Effectiveness %"; "res" -> "Resistance %"; else -> t
}

@Composable
private fun ArtifactSheet(model: AppModel, sheet: Sheet) {
    model.rev
    val heroId = sheet.heroId
    val h = model.hero(heroId) ?: return
    val role = model.gameHero(h)?.role
    var q by remember { mutableStateOf("") }
    var stars by remember { mutableIntStateOf(0) }
    val cfg = model.config(heroId)
    val fromPreset = model.content.artifact(model.request(heroId).artifact)
    fun pick(code: String?) { cfg.artifact = code; model.touch(); model.save(); model.sheet = sheet.back?.let { Sheet(it, heroId) } }
    SheetBody("Artifact", trailing = { Text(model.heroName(h), style = zen(12.sp, color = C.muted)) }) {
        SearchField(q, "Find artifact") { q = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "All", 5 to "5★", 4 to "4★", 3 to "3★ and lower").forEach { (n, label) -> Pill(label, selected = stars == n, onClick = { stars = n }) }
        }
        // only what this hero's class can wear: its own class's artifacts and the ones for every class
        val list = model.content.artifacts.filter { (role == null || it.role == null || it.role == role) && (q.isBlank() || it.name.contains(q, true)) &&
            (stars == 0 || if (stars == 3) it.rarity <= 3 else it.rarity == stars) }
            .sortedBy { if (it.role == role) 0 else 1 }
        Row(Modifier.fillMaxWidth().tap { pick(null) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtifactIcon(model, fromPreset, 32.dp)
            Column(Modifier.weight(1f)) {
                Text("Use the preset's artifact", style = zen(13.sp, color = if (cfg.artifact == null) C.deepPink else C.blue))
                Text(fromPreset?.name ?: "The preset names none", style = zen(10.sp, FontWeight.Medium, C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(16.dp)) { if (cfg.artifact == null) CheckMark(16.dp, C.deepPink) }
        }
        list.take(120).forEach { a ->
            val on = a.code == cfg.artifact
            Row(Modifier.fillMaxWidth().tap { pick(a.code) }
                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArtifactIcon(model, a, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = zen(13.sp, color = if (on) C.deepPink else C.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // some artifacts exist twice: the class one and a later all-class one with the same name
                    Text((if (a.role == null) "All classes · " else "") +
                        listOfNotNull("Atk ${fmtNum(a.atk)}".takeIf { a.atk > 0 }, "Hp ${fmtNum(a.hp)}", "Def ${fmtNum(a.def)}".takeIf { a.def > 0 }).joinToString(" · ") + " at +30",
                        style = zen(10.sp, FontWeight.Medium, C.muted))
                }
                Box(Modifier.size(16.dp)) { if (on) CheckMark(16.dp, C.deepPink) }
            }
        }
    }
}

@Composable
fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.fillMaxWidth().height(40.dp).clip(shape).background(C.card).border(2.dp, C.ink, shape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, style = zen(13.sp, FontWeight.Medium, C.faint))
        BasicTextField(value, onChange, textStyle = zen(13.sp), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetsSheet(model: AppModel, heroId: Long) {
    model.rev
    val req = model.request(heroId)
    val choice = SetChoice.from(req.setAlts, model.content)
    fun commit(c: SetChoice) { req.setAlts = c.toAlts(); model.touch() }
    SheetBody("Sets", trailing = { LinkText("Any sets") { commit(SetChoice(emptyList(), emptyMap())) } }, button = "Done",
        onButton = { model.save(); model.sheet = null }) {
        Text("4-piece: pick every set you'd accept. With one, the 2-piece picks are its partner (any one of them). " +
            "Without one, the + on a picked 2-piece set asks for ×2 or ×3. A tap on a set drops it.",
            style = zen(12.sp, FontWeight.Medium, C.muted))
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Needs:", style = zen(13.sp))
            SetChoiceSummary(model, choice, 20.dp)
        }
        for ((title, pieces) in listOf("4-piece · any one of" to 4, "2-piece · + for ×2, ×3" to 2)) {
            SectionTitle(title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.content.sets.filter { it.pieces == pieces }.forEach { st ->
                    val n = if (pieces == 4) (if (st.key in choice.four) 1 else 0) else choice.two[st.key] ?: 0
                    val on = n > 0
                    // same size selected or not, so the grid never jumps; the count sits on the tile's corner
                    Box(Modifier.width(62.dp)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) C.lightPink else C.card)
                            .border(2.dp, if (on) C.ink else C.line, RoundedCornerShape(12.dp))
                            .tap { commit(if (pieces == 4) choice.tapFour(st.key) else choice.tapTwo(st.key)) }
                            .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            SetIcon(st.icon, 28.dp)
                            Text(st.name, style = zen(9.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // its own tap target, a little bigger than it looks; the tile under it only picks or drops
                        if (pieces == 2 && choice.canRepeat(st.key)) Box(Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp)
                            .semantics { contentDescription = "More ${st.name}" }.tap { commit(choice.repeatTwo(st.key)) }.padding(4.dp)) {
                            Box(Modifier.defaultMinSize(minWidth = 20.dp).clip(RoundedCornerShape(6.dp)).background(C.deepPink)
                                .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(if (n >= 2) "×$n" else "+", style = dela(11.sp, C.card))
                            }
                        }
                    }
                }
            }
        }
    }
}


/** "Speed / Revenge 4 + Crit / Hit 2", or "Health ×2 + Crit" without a 4-piece set, in icons on one line. */
@Composable
fun SetChoiceSummary(model: AppModel, c: SetChoice, size: androidx.compose.ui.unit.Dp) {
    if (c.four.isEmpty() && c.two.isEmpty()) { Text("any", style = zen(13.sp, color = C.muted)); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        @Composable fun anyOf(keys: Collection<String>, n: String) {
            keys.forEachIndexed { i, k ->
                if (i > 0) Text("/", style = zen(11.sp, color = C.muted))
                SetIcon(model.content.set(k)?.icon, size)
            }
            Text(n, style = zen(12.sp))
        }
        when {
            c.mix != null && c.four.isEmpty() -> anyOf(c.two.keys, "· ${c.mix.firstOrNull()?.values?.sum()?.div(2) ?: 0} pairs, any mix")
            c.four.isNotEmpty() -> {
                anyOf(c.four, "4")
                if (c.two.isNotEmpty()) { Text("+", style = zen(12.sp, color = C.muted)); anyOf(c.two.keys, "2") }
            }
            else -> c.two.entries.forEachIndexed { i, (k, n) ->
                if (i > 0) Text("+", style = zen(12.sp, color = C.muted))
                SetIcon(model.content.set(k)?.icon, size)
                if (n > 1) Text("×$n", style = zen(12.sp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainsSheet(model: AppModel, heroId: Long) {
    model.rev
    val req = model.request(heroId)
    SheetBody("Mains", trailing = { LinkText("Any") { req.mains = emptyMap(); model.touch() } }, button = "Done",
        onButton = { model.save(); model.sheet = null }) {
        Text("Main stats allowed on each accessory. None selected means any.", style = zen(12.sp, FontWeight.Medium, C.muted))
        for (slot in Slot.ACCESSORIES) {
            SectionTitle(slot.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Ghost.ACCESSORY_MAINS.getValue(slot).forEach { g ->
                    val on = req.mains[slot]?.contains(g) == true
                    Pill(g.short, selected = on, leading = { StatIcon(g.stat) }, onClick = {
                        val s = (req.mains[slot] ?: emptySet()).let { if (on) it - g else it + g }
                        req.mains = (req.mains + (slot to s)).filterValues { it.isNotEmpty() }
                        model.touch()
                    })
                }
            }
        }
    }
}

@Composable
private fun GoalsSheet(model: AppModel, heroId: Long) {
    model.rev
    val req = model.request(heroId)
    val desc = mapOf(
        Goal.TARGETS to "Lands each stat on its target; misses cost by weight. Best with a preset.",
        Goal.DAMAGE to "Average hit of a skill from the game's own skill data: attack, plus the hero's health, defense or speed where the skill scales off them; crit and Penetration included.",
        Goal.EHP to "Health × defense: how much damage the hero survives.",
        Goal.SPEED to "Fastest build that meets your minimums.",
        Goal.CP to "The game's combat power formula.",
        Goal.GEAR_SCORE to "Sum of the pieces' substat scores, as in Fribbels.",
    )
    SheetBody("Goal", button = "Done", onButton = { model.save(); model.sheet = null }) {
        Goal.entries.forEach { g ->
            val on = req.goal == g
            Card(shadow = if (on) C.pink else null, radius = 14.dp, border = 2.dp, bg = if (on) C.lightPink else C.card,
                onClick = { req.goal = g; model.touch() }) {
                Text(g.label.replaceFirstChar { it.uppercase() }, style = dela(14.sp))
                Text(desc.getValue(g), style = zen(11.sp, FontWeight.Medium, C.muted))
                val skills = model.hero(heroId)?.let { model.gameHero(it) }?.skills.orEmpty()
                if (g == Goal.DAMAGE && skills.isNotEmpty()) {
                    VSpace(6.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf(0) + skills.map { it.n }).forEach { n ->
                            Pill(if (n == 0) "Hardest-hitting" else "S$n", selected = req.skill == n,
                                onClick = { req.goal = Goal.DAMAGE; req.skill = n; model.touch() })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowSheet(model: AppModel) {
    SheetBody("Show") {
        Text("The number on each result card. Results stay ranked by the goal.", style = zen(12.sp, FontWeight.Medium, C.muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Rating.entries.forEach { r ->
                Pill(r.label, selected = model.showRating == r, onClick = { model.showRating = r; model.save(); model.sheet = null })
            }
        }
    }
}

@Composable
private fun HeroPickSheet(model: AppModel, sheet: Sheet) {
    val box = model.box ?: return
    val f = remember { HeroFilter() }
    SheetBody("Choose hero") {
        HeroFilterBar(f)
        box.heroes.sortedWith(compareByDescending<OwnedHero> { model.tier(it.id) }.thenBy { model.heroName(it) })
            .filter { f.matches(model, it) }
            .forEach { h ->
                Row(Modifier.fillMaxWidth().tap {
                    model.pickHero(h.id)
                    model.sheet = null
                }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Face(model.face(h), 36.dp, ring = if (model.tier(h.id) >= 3) C.pink else C.faint)
                    Text(model.heroName(h), style = zen(14.sp), modifier = Modifier.weight(1f))
                    TierDots(model.tier(h.id), 8.dp)
                    if (model.gameHero(h) == null) Text("no data", style = zen(10.sp, color = C.red))
                }
            }
    }
}
