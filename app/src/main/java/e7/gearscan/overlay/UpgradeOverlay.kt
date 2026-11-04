package e7.gearscan.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppGraph
import e7.gearscan.data.*
import e7.gearscan.features.tracker.TrackerFeature
import e7.gearscan.opt.EsForecast
import e7.gearscan.opt.GearBounds
import e7.gearscan.opt.GearPotential
import e7.gearscan.features.sellreview.EsChip
import e7.gearscan.features.sellreview.esKeeps
import e7.gearscan.features.sellreview.esRollsOn
import e7.gearscan.ui.*
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

private enum class UpgradePane { PIECE, GOAL, ADVICE, EQUIP }
private enum class EntryPage { CHOOSE, VALUE, REVIEW }

/** A draft stays separate from saved observations until the explicit Save action. */
private class UpgradeDraft(val record: UpgradeRecord, val correction: Boolean) {
    val item = record.item
    val next = if (correction) item.enhance else ((item.enhance / 3 + 1) * 3).coerceAtMost(15)
    val main = GearBounds.main(record, next).minimum
    val reveal = !correction && item.rank == "Heroic" && item.subs.size == 3 && next == 12
    var values by mutableStateOf(item.subs.map { it.value })
    var added by mutableStateOf<GearStat?>(null)
    var newValue by mutableStateOf<Double?>(null)
    var field by mutableIntStateOf(0)
    var input by mutableDoubleStateOf(0.0)
    var page by mutableStateOf(EntryPage.CHOOSE)
    val type get() = if (reveal) added!! else item.subs[field].type
    val bounds get() = if (reveal) GearBounds.newLine(type, item.level) else GearBounds.existingLine(item, field, next, correction)
    val choiceStats get() = if (reveal) UpgradeRecord.allowedNew(item) else item.subs.map { it.type }
    val choiceNames get() = if (reveal) choiceStats.map { it.short } else item.subs.mapIndexed { i, s -> "${s.type.short}: ${fmtNum(values[i])}" }
    val choiceTexts get() = if (reveal) choiceNames else item.subs.mapIndexed { i, s -> s.type.format(values[i]) }
    fun choose(index: Int) {
        if (!correction) values = item.subs.map { it.value }
        if (reveal) { added = UpgradeRecord.allowedNew(item)[index]; newValue = null } else field = index
        val initial = if (reveal) bounds.minimum else values[field]
        input = initial.coerceIn(bounds.minimum, bounds.maximum)
        page = EntryPage.VALUE
    }
    fun apply() {
        if (reveal) newValue = input else values = values.mapIndexed { i, v -> if (i == field) input else v }
        page = if (correction) EntryPage.CHOOSE else EntryPage.REVIEW
    }
    fun preview() = Item(item.id, item.slot, item.set, item.rank, item.level, next, item.mainType, main,
        item.subs.mapIndexed { i, s -> Substat(s.type, values[i], s.rolls, s.modified) } +
            listOfNotNull(added?.let { t -> newValue?.let { Substat(t, it, 1, false) } }), item.locked, item.equippedOn, main15 = item.main15)
    fun save(t: TrackerFeature) = t.commit(main, values, if (reveal) added?.let { it to (newValue ?: Double.NaN) } else null, correction)
}

/** The rail's rows, in dp; the body takes what is left of the screen height under [RAIL_TOP]. */
private const val RAIL_PAD = 6f
private const val TOP_ROW = 32f
private const val TOP_ROW_COMPACT = 24f
private const val NAV_ROW = 30f
private const val COUNT_ROW = 14f
private const val ACTION = 34f
private const val ACTION_COMPACT = 28f
private const val SMALL = 28f
private const val STAT_SIZE = 11
private const val STAT_SIZE_SMALL = 9
private const val GAP = 4f

/** The least room number entry needs to stay readable. */
private const val BODY_FLOOR = 96f

/** Room a draft page needs: every choice in two columns, or the value slider. */
private fun UpgradeDraft.need(): Float = when (page) {
    EntryPage.CHOOSE -> 20f + 34f * ((choiceNames.size + 1) / 2)
    EntryPage.VALUE -> 150f
    EntryPage.REVIEW -> 0f
}

private fun UpgradeDraft.buttons() = if (page == EntryPage.REVIEW || correction && page == EntryPage.CHOOSE) 2 else 1

/** The tracker is a narrow rail that fits the game's left bar and ends at its content. Lists, long explanations and editing use
 *  explicit pages, never scroll containers. */
@Composable
fun UpgradeOverlay(g: AppGraph, onMove: (Float, Float) -> Unit, onDock: (Boolean) -> Unit, onClose: () -> Unit) {
    val t = g.tracker
    val w = g.overlay
    val r = t.record ?: return
    val item = r.item
    var pane by remember { mutableStateOf(UpgradePane.PIECE) }
    val draft = remember(item, t.editing) { if (t.editing) UpgradeDraft(r, t.correcting) else null }
    if (!w.expanded) {
        // the ring fills with the enhance level, in the piece's rank colour
        BubbleShell(w, item.enhance / 15f, rankColor(item.rank), C.onTile.copy(alpha = 0.25f), chanceBadge(t), { w.expanded = true; onDock(true) },
            corner = g.content.content.set(item.set)?.icon?.let { icon -> { SetIcon(icon, 18.dp) } }) {
            Box(Modifier.fillMaxSize().background(C.slate), contentAlignment = Alignment.Center) {
                GearIcon(item.slot.icon, 30.dp, C.onTile)
                Text("+${item.enhance}", style = dela(9.sp, C.onTile), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            }
        }
        return
    }
    val config = LocalConfiguration.current
    val usable = (if (w.usableHeightDp > 0) min(config.screenHeightDp.toFloat(), w.usableHeightDp) else config.screenHeightDp.toFloat()) - RAIL_TOP
    val es by produceState<EsForecast.Es?>(null, item, t.scoreQuery, t.cleared) {
        value = if (t.hasEs) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { t.readout.esFor(item) } else null
    }
    // a sell walk moves on with Sold and Keep (↶ goes back), so it shows only its count
    val countRow = t.queue.isNotEmpty() && t.isSellQueue
    val navWalk = t.queue.size > 1 && !t.isSellQueue
    val queueRow = navWalk && pane == UpgradePane.PIECE && !t.editing
    val slots = if (t.isSellQueue) 3 else 2
    val rows = if (countRow) COUNT_ROW + GAP else if (navWalk) NAV_ROW + GAP else 0f
    val recordable = item.enhance < 15 && UpgradeRecord.supported(item)
    val pieceButtons = if (t.isSellQueue) 2 + (if (recordable) 1 else 0)
        else listOf(recordable, item.enhance == 15 && item.level == 85 && t.canReforge(item.id),
            item.enhance == 15 && t.checkRun.equipHeroes.isNotEmpty() && t.worn.equippedOn == null).count { it }
    fun chromeOf(top: Float, action: Float) = 2 * RAIL_PAD + top + 2 * GAP + slots * action + (slots - 1) * GAP + rows
    val openVerdict = { if (!t.editing) pane = if (!t.hasTarget) UpgradePane.ADVICE else UpgradePane.GOAL }
    val need = draft?.need() ?: 0f
    val shape = RoundedCornerShape(14.dp)
    val rail: @Composable (Boolean, Int) -> Unit = { roomy, statSize ->
        val topRow = if (roomy) TOP_ROW else TOP_ROW_COMPACT
        val action = if (roomy) ACTION else ACTION_COMPACT
        val chrome = chromeOf(topRow, action)
        val bodyCap = (usable - 12 - chrome).coerceAtLeast(BODY_FLOOR)
        Column(Modifier.popIn(w).padding(end = 4.dp, bottom = 4.dp).width(RAIL_WIDTH.dp).hardShadow(C.pink, 3.dp, 3.dp, 14.dp).clip(shape).background(C.card)
            .border(2.dp, C.edge, shape).testTag("gear-tracker-panel").padding(RAIL_PAD.dp), verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
            Row(Modifier.fillMaxWidth().height(topRow.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                // everything else (correct, history, stop) lives in E7 Gear's Track upgrades
                PanelAction("<", Modifier.weight(1f), enabled = !t.editing, label = "Back to E7 Gear") { pane = UpgradePane.PIECE; t.returnToApp() }
                if (t.isSellQueue) PanelAction("", Modifier.weight(1f), enabled = t.canUndoSold && !t.editing, label = "Undo last mark", icon = { UndoArrow(it) }) { t.undoSold(); pane = UpgradePane.PIECE }
                PanelAction("–", Modifier.weight(1f), label = "Collapse tracker") { w.expanded = false; onDock(false) }
            }
            val countText = if (t.sellQueueComplete) "0 left" else if (t.needsRollRefresh) "Refresh" else "${t.queueIndex + 1}/${t.pendingQueue.size}"
            if (countRow) Box(Modifier.fillMaxWidth().height(COUNT_ROW.dp).dragWindow(onMove), contentAlignment = Alignment.Center) {
                FitText(countText, zen(9.sp, color = C.blue))
            }
            if (queueRow) Row(Modifier.fillMaxWidth().height(NAV_ROW.dp), horizontalArrangement = Arrangement.spacedBy(GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelAction("‹", Modifier.width(SMALL.dp), enabled = t.queueIndex > 0 && !t.editing, label = "Previous gear") { t.selectInQueue(t.queueIndex - 1); pane = UpgradePane.PIECE }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FitText(countText, zen(10.sp, color = C.blue))
                }
                PanelAction("›", Modifier.width(SMALL.dp), enabled = t.queueIndex < t.pendingQueue.lastIndex && !t.editing, label = "Next gear") { t.selectInQueue(t.queueIndex + 1); pane = UpgradePane.PIECE }
            }
            PanelBody(Modifier.fillMaxWidth(), floor = BODY_FLOOR.dp, cap = bodyCap.dp, need = need.dp,
                // the nav row's room while it hides, and a draft's unused button slots: the rail keeps its height and Back stays put
                extra = ((if (navWalk && !queueRow) NAV_ROW + GAP else 0f) +
                    (draft?.let { (pieceButtons - it.buttons()) * (action + GAP) } ?: 0f)).dp,
                natural = { RailPiece(t, item.fourLines(), 10_000f, es, onMove, measure = true, statSize = statSize) {} }) { fits ->
            BoxWithConstraints(Modifier.fillMaxSize().testTag("gear-tracker-body")) {
                when {
                    t.error != null -> PanelTextPages(t.error!!, maxHeight.value)
                    t.sellQueueComplete -> PanelTextPages("List complete\n\nEvery piece in this list is marked sold or kept.\n\nRescan after selling to refresh the game inventory.", maxHeight.value)
                    draft != null -> DraftBody(draft, maxHeight.value)
                    pane == UpgradePane.PIECE -> RailPiece(t, item, if (fits) maxHeight.value else 0f, es, onMove, statSize = statSize, onVerdict = openVerdict)
                    pane == UpgradePane.GOAL -> GoalPage(t, maxHeight.value)
                    pane == UpgradePane.ADVICE -> PanelTextPages(adviceText(t), maxHeight.value)
                    pane == UpgradePane.EQUIP -> PanelChoices(t.checkRun.equipHeroes.map { "${it.name} · ${it.nowGain}" }, maxHeight.value, "Equipped on") { i ->
                        t.checkRun.equipHeroes.getOrNull(i)?.let { h -> t.worn.equip(h.id) }; pane = UpgradePane.PIECE
                    }
                }
            }
            }
            // as tall as the buttons this page has: the body above keeps its height, so the first button never moves
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GAP.dp)) {
                val b = Modifier.fillMaxWidth().height(action.dp)
                when {
                    t.error != null -> PanelAction("Back", b) { t.dismissError() }
                    draft != null -> {
                        if (draft.page == EntryPage.REVIEW || draft.correction && draft.page == EntryPage.CHOOSE) PanelAction("Save +${draft.next}", b, pink = true) {
                            if (draft.save(t)) pane = UpgradePane.PIECE
                        }
                        PanelAction(if (draft.page == EntryPage.CHOOSE) "Cancel" else "Back", b) {
                            when (draft.page) {
                                EntryPage.CHOOSE -> { t.cancelEdit(); pane = UpgradePane.PIECE }
                                EntryPage.REVIEW -> draft.page = EntryPage.VALUE
                                EntryPage.VALUE -> draft.page = EntryPage.CHOOSE
                            }
                        }
                    }
                    t.sellQueueComplete -> PanelAction("Undo mark", b, pink = true, enabled = t.canUndoSold) { t.undoSold(); pane = UpgradePane.PIECE }
                    pane == UpgradePane.PIECE -> {
                        // the pink button is what the forecast says to do next: sell it, enhance it on, or keep it
                        val record = item.enhance < 15 && UpgradeRecord.supported(item)
                        val keeps = es?.let { esKeeps(it) }
                        val rollOn = record && es?.let { esRollsOn(it, t.rollOn) } == true
                        if (t.isSellQueue) {
                            PanelAction("Sold ›", b, pink = keeps == false, enabled = t.canMarkSold, label = "Sold and next") { t.markSold(item.id); pane = UpgradePane.PIECE }
                            PanelAction("Keep ›", b, pink = keeps == true && !rollOn, enabled = t.canMarkSold, label = "Keep and next") { t.markSold(item.id, keep = true); pane = UpgradePane.PIECE }
                        }
                        // a goal walk: Record is the next step only while the targets are reachable and not reached
                        val goalOn = if (t.goal != null || t.scoreGoal != null) t.readout.goalOutcome?.keepRolling == true else true
                        if (record) PanelAction("Record +${((item.enhance / 3 + 1) * 3).coerceAtMost(15)}", b, pink = if (t.isSellQueue) rollOn else goalOn) {
                            t.beginEdit()
                        }
                        // a finished piece: record the reforge, then equipping it, as done in the game
                        if (!t.isSellQueue && item.enhance == 15 && item.level == 85 && t.canReforge(item.id))
                            PanelAction("Reforge", b, pink = if (t.goal != null || t.scoreGoal != null) t.readout.goalOutcome?.let { it.chance > 0 && !it.matchesNow } == true
                                else t.checkRun.equipHeroes.isEmpty(), label = "Record reforge") { t.reforge() }
                        if (!t.isSellQueue && item.enhance == 15 && t.checkRun.equipHeroes.isNotEmpty() && t.worn.equippedOn == null)
                            PanelAction("Equip", b, pink = true, label = "Record equip") { pane = UpgradePane.EQUIP }
                    }
                    else -> {
                        PanelAction("Back", b) { pane = UpgradePane.PIECE }
                    }
                }
            }
        }
    }
    // the piece is measured first; short of room it keeps its cell by giving up, in turn, the bigger buttons and then the bigger numbers
    SubcomposeLayout { c ->
        val natural = subcompose("natural") { Box(Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { }) { RailPiece(t, item.fourLines(), 10_000f, es, onMove, measure = true) {} } }
            .maxOfOrNull { it.measure(Constraints(maxWidth = (RAIL_WIDTH - 2 * RAIL_PAD).dp.roundToPx())).height }?.toDp()?.value ?: 0f
        val roomy = usable - 12 - chromeOf(TOP_ROW, ACTION) >= natural
        val statSize = if (roomy || usable - 12 - chromeOf(TOP_ROW_COMPACT, ACTION_COMPACT) >= natural) STAT_SIZE else STAT_SIZE_SMALL
        val placed = subcompose("rail") { rail(roomy, statSize) }.map { it.measure(c) }
        layout(placed.maxOfOrNull { it.width } ?: 0, placed.maxOfOrNull { it.height } ?: 0) { placed.forEach { it.place(0, 0) } }
    }
}

/** As tall as [natural] (the piece page drawn with room to spare) for every page of this piece, so the buttons below don't move. */
@Composable
private fun PanelBody(modifier: Modifier, floor: Dp, cap: Dp, need: Dp, extra: Dp, natural: @Composable () -> Unit, content: @Composable (fits: Boolean) -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val width = c.maxWidth
        // only measured, never shown: hidden from accessibility so the piece isn't read (or found by tests) twice
        val measured = subcompose("natural") { Box(Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { }) { natural() } }
            .maxOfOrNull { it.measure(Constraints(maxWidth = width)).height } ?: 0
        val capPx = cap.roundToPx()
        val height = maxOf(measured.coerceIn(minOf(floor.roundToPx(), capPx), capPx) + extra.roundToPx(), minOf(need.roundToPx(), capPx + extra.roundToPx())).coerceAtLeast(0)
        val fits = height - extra.roundToPx() >= measured
        val placeables = subcompose("body") { content(fits) }.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

// a three-substat piece is measured as four, so Sold and Keep stay put from piece to piece
private fun Item.fourLines(): Item = if (subs.size >= 4 || subs.isEmpty()) this
    else Item(id, slot, set, rank, level, enhance, mainType, mainValue, subs + List(4 - subs.size) { subs.first() }, locked, equippedOn, main15 = main15)

private fun chanceBadge(t: TrackerFeature) = if (!t.hasTarget && t.checkRun.results.isNotEmpty() && !t.checkRun.busy && t.scoreQuery == null)
    when { t.worn.equippedOn != null -> "Done"; t.record?.item?.enhance == 15 -> if (t.checkRun.equipHeroes.isNotEmpty()) "Equip" else "None"; else -> t.checkRun.chance?.let { "$it%" } ?: "Advice" }
    else if(t.sellQueueComplete)"Done" else if(t.isRollReview)if(t.readout.reviewProtected)"Guard" else "Review" else t.readout.goalOutcome?.let { when { !it.supported -> "Unknown"; t.scoreGoal!=null -> if(it.matchesNow) "Above" else if(it.rareOnly) "Rare" else if(!it.possible) "0%" else it.label(); else -> it.word() } } ?: if (t.checkRun.busy) "Busy" else "Advice"

/** The piece as the game's inventory cell (tile, ES under it, stats stacked beside it), then the verdict, which opens its page. */
@Composable
private fun RailPiece(t: TrackerFeature, item: Item, height: Float, es: EsForecast.Es?, onMove: (Float, Float) -> Unit, measure: Boolean = false,
                      statSize: Int = STAT_SIZE, onVerdict: () -> Unit) {
    if (height < 60 * LocalDensity.current.fontScale + 12) {
        // too short for the tile: the stats as text pages, and a tap on them opens the verdict like the chip does
        Box(Modifier.fillMaxSize().tap(onVerdict).then(if (measure) Modifier else Modifier.semantics { contentDescription = "Verdict details" })) {
            PanelTextPages("${item.rank} Lv.${item.level} · ${item.mainType.short} ${item.mainType.format(item.mainValue)}\n" +
                item.subs.joinToString("\n") { "${it.type.short} ${it.type.format(it.value)}" }, height)
        }
        return
    }
    // the cell's numbers are sized for the rail, not the phone's font setting, so the cell fits beside the game at any setting
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(d.density, min(d.fontScale, 1f))) {
        Column(if (measure) Modifier else Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // the tile's +level badge sits above its corner: the top padding keeps it inside the body
            Row(Modifier.fillMaxWidth().dragWindow(onMove).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GearTile(item, 32.dp, setIcon = t.setIcon)
                    Text("${InventoryOrder.score(item)}", style = dela(11.sp))
                }
                StatStack(item, highlight = t.goal?.minimums?.keys ?: emptySet(), size = statSize)
            }
            // the verdict sits on the buttons, so a three-substat piece moves nothing
            if (!measure) Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().tap(onVerdict).then(if (measure) Modifier else Modifier.semantics { contentDescription = "Verdict details" }), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (t.hasEs) {
                    if (es != null) EsChip(es, t.rollOn, fill = true)
                    else if (measure) EsChip(EsForecast.Es(0, 1, 0.0, 1.0, 1.0, false, true, 1.0, 1.0), 0.0, fill = true, modifier = Modifier.alpha(0f))
                } else {
                    val badge = chanceBadge(t)
                    FitText(badge, dela(18.sp, if (badge in setOf("Rare", "Stop", "0%")) C.deepPink else C.blue))
                }
                t.goal?.let { GoalLines(item, it.minimums, size = 9) }
            }
        }
    }
}

@Composable
private fun DraftBody(d: UpgradeDraft, height: Float) {
    when (d.page) {
        EntryPage.CHOOSE -> {
            PanelChoices(d.choiceNames, height, if (d.reveal) "New fourth substat" else if (d.correction) "Correct totals · +${d.next}" else "Which stat increased?",
                columns = 2, stats = d.choiceStats, texts = d.choiceTexts) { d.choose(it) }
        }
        EntryPage.VALUE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ValueSlider(d.input, d.bounds.minimum, d.bounds.maximum, Modifier.weight(1f).fillMaxHeight(), { d.input = it }) { d.apply() }
            Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                StatIcon(d.type.stat, 14.dp, C.ink)
                FitText(d.type.short, zen(10.sp))
                FitText(fmtNum(d.input), dela(20.sp, C.blue))
            }
        }
        EntryPage.REVIEW -> if (height < 60 * LocalDensity.current.fontScale + 12) {
            PanelTextPages("Review +${d.next}\n${d.item.mainType.short} ${d.item.mainType.format(d.main)} · automatic\n" +
                d.preview().subs.joinToString("\n") { "${it.type.short} ${it.type.format(it.value)}" }, height)
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FitText("Check +${d.next}", zen(10.sp))
            StatStack(d.preview(), size = STAT_SIZE, highlight = setOfNotNull(if (d.reveal) d.added else d.item.subs.getOrNull(d.field)?.type))
        }
    }
}

@Composable
private fun PanelChoices(names: List<String>, height: Float, title: String, columns: Int = 1, stats: List<GearStat>? = null, texts: List<String> = names,
                         select: (Int) -> Unit) {
    val scale = LocalDensity.current.fontScale
    val rowsNeeded = (names.size + columns - 1) / columns
    val rows = floor((height - (if (title.isEmpty()) 8 else 20) - (if (rowsNeeded * 34 > height) NAV_ROW + 4 else 0f)) / 34).toInt().coerceAtLeast(1)
    val count = rows * columns
    var page by remember(names, count) { mutableIntStateOf(0) }
    val pages = ((names.size + count - 1) / count).coerceAtLeast(1)
    val titleHeight = (12.6f * scale).coerceAtLeast(16f)
    val rowsShown = min(rows, rowsNeeded).coerceAtLeast(1)
    val room = height - (if (title.isEmpty()) 0f else titleHeight + 2) - (if (pages > 1) NAV_ROW + 2 else 0f)
    val rowHeight = ((room - 4 * (rowsShown - 1)) / rowsShown).coerceIn(30f, 44f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title.isNotEmpty()) FitText(title, zen(9.sp), Modifier.height(titleHeight.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val shown = names.indices.drop(page * count).take(count)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = (maxWidth - 4.dp * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0 until (shown.size + columns - 1) / columns) Row(Modifier.fillMaxWidth().height(rowHeight.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                        shown.drop(r * columns).take(columns).forEach { index ->
                            val stat = stats?.getOrNull(index)
                            val icon: (@Composable (Color) -> Unit)? = stat?.let { st -> @Composable { ink: Color ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    StatIcon(st.stat, 11.dp, ink); FitText(texts[index], zen(10.sp, color = ink))
                                }
                            } }
                            PanelAction(texts[index], Modifier.width(cell), label = names[index], pad = if (icon != null) 2.dp else 5.dp, icon = icon) { select(index) }
                        }
                    }
                }
            }
        }
        if (pages > 1) PanelPager(page, pages, "${page + 1} / $pages", "choices") { page = it }
    }
}

@Composable
private fun GoalPage(t: TrackerFeature, height: Float) {
    if(t.isRollReview) {
        val rolls=t.readout.reviewRolls
        PanelTextPages("${t.readout.title}\n\n"+(rolls?.joinToString("\n") { r->"${if(r.enhance==0)"Start" else "+${r.enhance}${if(r.reveal)" new" else ""}"}: ${r.stat.short} +${r.stat.format(r.value)} (usual ${r.stat.format(r.range.first.toDouble())}–${r.stat.format(r.range.last.toDouble())})" }?:"Roll history unavailable")+"\n\n${t.readout.reviewStatText}\n\nEach stat limit is separate. Review the actual substat totals before selling. Recorded upgrades update this history.",height)
        return
    }
    t.scoreGoal?.let { goal->
        val item=t.record!!.item
        PanelTextPages("${goal.label()}\n${goal.referenceLabel()}\n\nNow: ${fmtNum(goal.model.value(item))} pts\nDiversity penalty: ${goal.model.diversity}%\nMain contribution: ${goal.model.mainPercent}%\n\n${t.readout.title}\n\n${if(goal.reforge) "Includes eligible reforging" else "Before reforging"}. This is a score filter, not a sell verdict.",height)
        return
    }
    val goal = t.goal ?: return
    val item = t.record!!.item
    val finish = if (goal.reforge && goal.reforgeAllowed && item.level == 85) "At +15 & reforged" else "At +15"
    PanelTextPages(finish + "\n\n" + goal.minimums.entries.joinToString("\n") { (stat, wanted) ->
        "${stat.short}: ${stat.format(item.subs.firstOrNull { it.type == stat }?.value ?: 0.0)} / ${stat.format(wanted)}"
    } + "\n\n${t.readout.title}\nChance is for every target together.", height)
}

/** Long notices/help/advice are split at rendered line boundaries for this exact body size and font scale. */
@Composable
private fun PanelTextPages(text: String, height: Float) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = zen(11.sp, color = C.muted)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val available = with(density) { (maxHeight - (NAV_ROW + 7).dp).roundToPx() }.coerceAtLeast(1)
        val chunks = remember(text, width, available, density.fontScale) {
            val layout = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = width))
            val line = measurer.measure(AnnotatedString("Ag"), style).size.height.coerceAtLeast(1)
            val lines = (available / line).coerceAtLeast(1)
            buildList {
                var start = 0
                while (start < layout.lineCount) {
                    val end = min(start + lines, layout.lineCount)
                    text.substring(layout.getLineStart(start), layout.getLineEnd(end - 1)).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    start = end
                }
            }.ifEmpty { listOf("") }
        }
        var page by remember(text, chunks) { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(1f)) { Text(chunks[page.coerceAtMost(chunks.lastIndex)], style = style) }
            PanelPager(page, chunks.size, "${page + 1} / ${chunks.size}", "details") { page = it }
        }
    }
}

/** The weakest finish that still helps the hero it helps most often, for the piece page. */
private fun weakestLine(t: TrackerFeature): String? {
    if (t.checkRun.busy || t.record?.item?.enhance == 15) return null
    t.checkRun.betterBet?.let { return "Better bet: ${it.second}" }
    val h = t.checkRun.results.filter { it.hits.isNotEmpty() }.maxByOrNull { it.hits.size } ?: return null
    return if (h.hits.size == h.total) "An upgrade whatever the rolls" else h.minimal.firstOrNull()?.let { "Upgrade at +15 from: $it" }
}

private fun adviceText(t: TrackerFeature) = buildList {
    add(t.readout.title)
    add(if (t.rebuilds) "Counting any rebuild: other pieces and sets may change." else "Counting this piece only: it goes into the hero's slot, nothing else moves.")
    if (t.checkRun.expected > 0 && t.checkRun.total > 0) add("${t.checkRun.results.size}/${t.checkRun.expected} saved setups checked. ${t.checkRun.hitCount} of ${t.checkRun.total} simulated finishes (${t.checkRun.chance}%) are an upgrade for at least one hero.")
    else if (t.checkRun.expected > 0) add("${t.checkRun.results.size}/${t.checkRun.expected} saved setups checked.")
    t.checkRun.results.filter { it.current || it.hits.isNotEmpty() || it.combo }.sortedByDescending { it.hits.size }.forEach { h ->
        add(buildString {
            append(h.name)
            if (h.current) append(" · an upgrade now${if (h.nowGain.isNotEmpty()) ", ${h.nowGain}" else ""}")
            if (h.combo) append(" · helps with other upgrades")
            if (h.hits.isNotEmpty()) append(" · ${h.hits.size}/${h.total} finishes do better${if (h.typical.isNotEmpty()) ", usually ${h.typical}" else ""}")
            if (h.hits.isNotEmpty() && h.hits.size == h.total) append("\nEvery finish does.")
            else if (h.minimal.isNotEmpty()) append("\nStill an upgrade at +15 from: " + h.minimal.joinToString("\nor "))
            if (h.changes.isNotEmpty()) append("\nBest: ${h.changes}")
            if (h.alts.isNotEmpty()) append("\n\n${h.name}'s unfinished ${t.record?.item?.slot?.label?.lowercase() ?: "piece"}s, best bet first (30 finishes each):\n" +
                h.alts.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n"))
        })
    }
    if (t.checkRun.expected > 0 && !t.checkRun.busy && t.checkRun.uncertain) add("Some searches are unfinished. No stop decision is certain; recheck it in E7 Gear to continue.")
}.joinToString("\n\n")

@Composable
private fun PanelPager(page: Int, pages: Int, text: String, kind: String, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(NAV_ROW.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelAction("‹", Modifier.width(SMALL.dp), enabled = page > 0, label = "Previous $kind page") { change(page - 1) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FitText(text, zen(9.sp, color = C.muted)) }
        PanelAction("›", Modifier.width(SMALL.dp), enabled = page + 1 < pages, label = "Next $kind page") { change(page + 1) }
    }
}

@Composable
private fun PanelAction(text: String, modifier: Modifier = Modifier, pink: Boolean = false, enabled: Boolean = true, label: String = text,
                        pad: Dp = 5.dp, icon: (@Composable (Color) -> Unit)? = null, click: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 27.dp).fillMaxHeight().then(if (enabled) Modifier.padding(bottom = 2.dp).hardShadow(C.edge, 0.dp, 2.dp, 8.dp) else Modifier)
        .clip(shape).background(if (!enabled) C.paper else if (pink) C.pink else C.card)
        .border(1.5.dp, if (pink || enabled) C.edge else C.line, shape).tap(if (enabled) click else null).semantics { contentDescription = label }.padding(horizontal = pad), contentAlignment = Alignment.Center) {
        val ink = if (!enabled) C.faint else if (pink) C.onAccent else C.ink
        if (icon != null) icon(ink) else FitText(text, zen(if (text.length == 1) 15.sp else 10.sp, color = ink))
    }
}

/** Up is more; the marks name every value while they fit. Lifting the finger picks the value. */
@Composable
private fun ValueSlider(value: Double, minimum: Double, maximum: Double, modifier: Modifier, onChange: (Double) -> Unit, onPick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val pick by rememberUpdatedState(onPick)
    val current by rememberUpdatedState(value)
    val measurer = rememberTextMeasurer()
    val style = zen(10.sp, color = C.muted)
    val span = (maximum - minimum).coerceAtLeast(0.0)
    Canvas(modifier
        .semantics {
            contentDescription = "Value slider"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), minimum.toFloat()..maximum.toFloat())
            setProgress { v -> change(round(v.toDouble()).coerceIn(minimum, maximum)); pick(); true }
        }
        .pointerInput(minimum, maximum) {
            val inset = 12.dp.toPx()
            fun at(y: Float) = (minimum + round((1 - ((y - inset) / (size.height - 2 * inset)).coerceIn(0f, 1f)) * span)).coerceIn(minimum, maximum)
            awaitEachGesture {
                var last = current
                fun set(v: Double) { if (v != last) { last = v; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); change(v) } }
                val down = awaitFirstDown()
                down.consume(); set(at(down.position.y))
                while (true) {
                    val event = awaitPointerEvent()
                    val p = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!p.pressed) break
                    p.consume(); set(at(p.position.y))
                }
                pick()
            }
        }) {
        val inset = 12.dp.toPx()
        val x = 9.dp.toPx()
        val length = size.height - 2 * inset
        fun y(v: Double) = inset + (1 - if (span == 0.0) 0.0 else (v - minimum) / span).toFloat() * length
        drawLine(C.line, Offset(x, inset), Offset(x, inset + length), 5.dp.toPx(), StrokeCap.Round)
        drawLine(C.pink, Offset(x, inset + length), Offset(x, y(value)), 5.dp.toPx(), StrokeCap.Round)
        val labelHeight = measurer.measure("0", style).size.height
        val every = listOf(1, 2, 5, 10, 20, 50, 100, 1000).firstOrNull { span == 0.0 || it * length / span >= labelHeight } ?: 1000
        val marks = (0..span.toInt()).filter { it % every == 0 }.map { minimum + it } + listOf(maximum)
        for (v in marks.distinct()) {
            if (v != maximum && v != minimum && y(v) - y(maximum) < labelHeight) continue
            val text = measurer.measure(fmtNum(v), if (v == value) style.copy(color = C.ink) else style)
            drawLine(C.muted, Offset(x + 4.dp.toPx(), y(v)), Offset(x + 8.dp.toPx(), y(v)), 1.5.dp.toPx())
            drawText(text, topLeft = Offset(x + 13.dp.toPx(), y(v) - text.size.height / 2f))
        }
        drawCircle(C.ink, 9.dp.toPx(), Offset(x, y(value))); drawCircle(C.card, 5.dp.toPx(), Offset(x, y(value)))
    }
}

/** A curved arrow turning back, drawn: the bundled font has no ↶. */
@Composable
private fun UndoArrow(color: Color) = Canvas(Modifier.size(13.dp)) {
    val r = size.minDimension * 0.36f
    val c = Offset(size.width * 0.55f, size.height * 0.58f)
    val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
    drawArc(color, 180f, 230f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = stroke)
    val tip = Offset(c.x - r, c.y + r * 0.15f)
    val h = r * 0.6f
    drawLine(color, tip, Offset(tip.x - h, tip.y - h), stroke.width, StrokeCap.Round)
    drawLine(color, tip, Offset(tip.x + h, tip.y - h), stroke.width, StrokeCap.Round)
}

/** A recessed block inside the panel: field fill and a thin rule, so sections read as layers. */
private fun Modifier.inset(): Modifier = this.clip(RoundedCornerShape(10.dp)).background(C.field).border(1.dp, C.line, RoundedCornerShape(10.dp))
