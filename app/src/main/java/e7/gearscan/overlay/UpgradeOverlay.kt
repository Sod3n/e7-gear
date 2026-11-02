package e7.gearscan.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.Color
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
import e7.gearscan.features.sellreview.EsBar
import e7.gearscan.features.sellreview.EsChip
import e7.gearscan.ui.*
import kotlin.math.floor
import kotlin.math.min

private enum class UpgradePane { PIECE, LIST, MENU, GOAL, HISTORY, HELP, ADVICE, EQUIP, BUILD }
private enum class EntryPage { CHOOSE, VALUE, REVIEW, STEP }

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
    var step by mutableDoubleStateOf(1.0)
    var page by mutableStateOf(EntryPage.CHOOSE)
    val type get() = if (reveal) added!! else item.subs[field].type
    val bounds get() = if (reveal) GearBounds.newLine(type, item.level) else GearBounds.existingLine(item, field, next, correction)
    val stepOptions get() = listOf(1.0, 10.0, 100.0, 1000.0).filter { it <= bounds.maximum - bounds.minimum }
    val choiceNames get() = if (reveal) UpgradeRecord.allowedNew(item).map { it.short } else item.subs.mapIndexed { i, s -> "${s.type.short}: ${fmtNum(values[i])}" }
    fun choose(index: Int) {
        if (!correction) values = item.subs.map { it.value }
        if (reveal) { added = UpgradeRecord.allowedNew(item)[index]; newValue = null } else field = index
        val initial = if (reveal) bounds.minimum else values[field]
        input = initial.coerceIn(bounds.minimum, bounds.maximum)
        step = 1.0; page = EntryPage.VALUE
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

/** The queue row and its gap; the body takes this room while the row is hidden, so the buttons stay put. */
private const val QUEUE_SLOT = 43f

/** Header, buttons, gaps and padding around the body, without the queue row. */
private const val PANEL_CHROME = 96f

/** The least room number entry needs to stay readable. */
private const val BODY_FLOOR = 96f

/** Height PanelChoices needs to show every choice on one page, or 0 when the draft page is not a chooser. */
private fun UpgradeDraft.chooserNeed(): Float {
    val count = when (page) { EntryPage.CHOOSE -> choiceNames.size; EntryPage.STEP -> stepOptions.size; else -> return 0f }
    return 55f + 34f * choiceRows(count)
}

private fun choiceRows(count: Int) = (count + choiceColumns(count) - 1) / choiceColumns(count)
private fun choiceColumns(count: Int) = if (count > 4) 3 else 2

/** The tracker is a panel that ends at its piece page. Lists, long explanations and editing use explicit pages, never scroll containers. */
@Composable
fun UpgradeOverlay(g: AppGraph, onMove: (Float, Float) -> Unit, onDock: (Boolean) -> Unit, onClose: () -> Unit) {
    val t = g.tracker
    val w = g.overlay
    val r = t.record ?: return
    val item = r.item
    var pane by remember { mutableStateOf(UpgradePane.PIECE) }
    var correction by remember { mutableStateOf(false) }
    val draft = remember(item, t.editing) { if (t.editing) UpgradeDraft(r, correction) else null }
    if (!w.expanded) {
        // the ring fills with the enhance level, in the piece's rank colour
        BubbleShell(w, item.enhance / 15f, rankColor(item.rank), C.onTile.copy(alpha = 0.25f), chanceBadge(t), { w.expanded = true; onDock(true) }) {
            Box(Modifier.fillMaxSize().background(C.slate), contentAlignment = Alignment.Center) {
                GearIcon(item.slot.icon, 30.dp, C.onTile)
                Text("+${item.enhance}", style = dela(9.sp, C.onTile), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            }
        }
        return
    }
    val config = LocalConfiguration.current
    val scale = LocalDensity.current.fontScale
    val usable = if (w.usableHeightDp > 0) min(config.screenHeightDp.toFloat(), w.usableHeightDp) else config.screenHeightDp.toFloat()
    val es by produceState<EsForecast.Es?>(null, item, t.scoreQuery, t.cleared) {
        value = if (t.hasEs) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { t.esFor(item) } else null
    }
    val queueRow = t.queue.isNotEmpty() && (pane == UpgradePane.PIECE || pane == UpgradePane.LIST) && !t.editing
    val bodyCap = (usable - 12).coerceAtLeast(160f) - PANEL_CHROME - if (t.queue.isNotEmpty()) QUEUE_SLOT else 0f
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.popIn(w).width(PANEL_WIDTH.dp).clip(shape).background(C.card).border(2.dp, C.edge, shape)
        .testTag("gear-tracker-panel").padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).dragWindow(onMove), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GearTile(item, 30.dp, setIcon = t.setIcon)
                Column(Modifier.weight(1f)) {
                    FitText(if(t.sellQueueComplete)"List complete" else item.slot.label, dela((if (scale > 1.3f) 10 else 11).sp))
                    FitText(if(t.sellQueueComplete)"All pieces marked" else t.setName ?: item.set.removeSuffix("Set"), zen((if (scale > 1.3f) 8 else 9).sp, color = C.muted))
                }
            }
            if (!t.isSellQueue) Box(Modifier.width(48.dp).tap { if (!t.editing) pane = if (!t.hasTarget) UpgradePane.ADVICE else UpgradePane.GOAL }, contentAlignment = Alignment.Center) {
                FitText(chanceBadge(t), dela(12.sp, C.blue))
            }
            if(t.canUndoSold&&!t.sellQueueComplete&&!t.editing)PanelAction("Undo",Modifier.width(38.dp),label="Undo last mark") { t.undoSold();pane=UpgradePane.PIECE }
            else if (t.canUndoEquip && !t.editing) PanelAction("Undo", Modifier.width(38.dp), label = "Undo equip") { t.undoEquip(); pane = UpgradePane.PIECE }
            PanelAction(if (t.notice == null) "···" else "···!", Modifier.width(34.dp), enabled = !t.editing, label = "More") { pane = if (pane == UpgradePane.MENU) UpgradePane.PIECE else UpgradePane.MENU }
            PanelAction("–", Modifier.width(30.dp), label = "Collapse tracker") { w.expanded = false; onDock(false) }
            PanelAction("×", Modifier.width(30.dp), label = "Close tracker", click = onClose)
        }
        if (queueRow) Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelAction("‹", Modifier.width(60.dp), enabled = t.queueIndex > 0 && !t.editing, label = "Previous gear") { t.selectInQueue(t.queueIndex - 1); pane = UpgradePane.PIECE }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FitText(if(t.sellQueueComplete)"0 remaining" else if(t.needsRollRefresh)"Refresh list for overall ranking" else if(t.isSellQueue)"${t.queueIndex + 1} / ${t.pendingQueue.size} · sell in E7 first" else "${t.queueIndex + 1} / ${t.pendingQueue.size} matches", zen(10.sp, color = C.blue)) }
            if (!t.isSellQueue) PanelAction(if (pane == UpgradePane.LIST) "Piece" else "List", Modifier.width(46.dp), enabled = !t.editing&&!t.sellQueueComplete) { pane = if (pane == UpgradePane.LIST) UpgradePane.PIECE else UpgradePane.LIST }
            PanelAction("›", Modifier.width(60.dp), enabled = t.queueIndex < t.pendingQueue.lastIndex && !t.editing, label = "Next gear") { t.selectInQueue(t.queueIndex + 1); pane = UpgradePane.PIECE }
        }
        PanelBody(Modifier.fillMaxWidth(), floor = BODY_FLOOR.dp, cap = bodyCap.dp, need = (draft?.chooserNeed() ?: 0f).dp, extra = if (t.queue.isNotEmpty() && !queueRow) QUEUE_SLOT.dp else 0.dp,
            natural = { PieceSummary(t, item, 10_000f, es, measure = true) }) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 2.dp).testTag("gear-tracker-body")) {
            when {
                t.error != null -> PanelTextPages(t.error!!, maxHeight.value)
                t.sellQueueComplete -> PanelTextPages("List complete\n\nEvery piece in this list is marked sold or kept.\n\nUndo mark restores the last local mark. Rescan after selling to refresh the game inventory.",maxHeight.value)
                draft != null -> DraftBody(draft, maxHeight.value)
                pane == UpgradePane.PIECE -> PieceSummary(t, item, maxHeight.value, es)
                pane == UpgradePane.LIST -> QueuePage(g, maxHeight.value) { pane = UpgradePane.PIECE }
                pane == UpgradePane.MENU -> {
                    val options = listOfNotNull("Open E7 Gear", if(t.isRollReview)"Roll review" else if (!t.hasTarget) "Build advice" else "Targets", "History", "Help") + listOfNotNull("Correct".takeIf { UpgradeRecord.supported(item) }) +
                        listOfNotNull("Track the better bet".takeIf { t.checkRun.betterBet != null },
                            "Final build".takeIf { !t.hasTarget && t.checkRun.results.any { it.build.isNotEmpty() && (it.hits.isNotEmpty() || it.current) } },
                            (if (t.rebuilds) "This piece only" else "Allow rebuilds").takeIf { !t.hasTarget },
                            "Recheck builds".takeIf { !t.hasTarget }, "Notice".takeIf { t.notice != null })
                    PanelChoices(options, maxHeight.value, "") { i -> when (options[i]) {
                        "Open E7 Gear" -> { pane = UpgradePane.PIECE; t.returnToApp() }
                        "Targets", "Roll review" -> pane = UpgradePane.GOAL
                        "Build advice" -> pane = UpgradePane.ADVICE
                        "History" -> pane = UpgradePane.HISTORY
                        "Correct" -> if (UpgradeRecord.supported(item)) { correction = true; t.beginEdit() }
                        "Help" -> pane = UpgradePane.HELP
                        "Recheck builds" -> { t.checkRun.runCheck(); pane = UpgradePane.ADVICE }
                        "Final build" -> pane = UpgradePane.BUILD
                        "Track the better bet" -> { t.checkRun.betterBet?.let { t.switchTo(it.first) }; pane = UpgradePane.PIECE }
                        "This piece only", "Allow rebuilds" -> { t.useRebuilds(!t.rebuilds); pane = UpgradePane.ADVICE }
                        "Notice" -> pane = UpgradePane.HELP
                    } }
                }
                pane == UpgradePane.GOAL -> GoalPage(t, maxHeight.value)
                pane == UpgradePane.HISTORY -> HistoryPage(r, maxHeight.value, t.goal?.minimums?.keys ?: emptySet())
                pane == UpgradePane.HELP -> PanelTextPages(helpText(t), maxHeight.value)
                pane == UpgradePane.ADVICE -> PanelTextPages(adviceText(t), maxHeight.value)
                pane == UpgradePane.BUILD -> PanelTextPages(buildText(t), maxHeight.value)
                pane == UpgradePane.EQUIP -> PanelChoices(t.checkRun.equipHeroes.map { "${it.name} · ${it.nowGain}" }, maxHeight.value, "Equipped on") { i ->
                    t.checkRun.equipHeroes.getOrNull(i)?.let { h -> t.equip(h.id) }; pane = UpgradePane.PIECE
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            when {
                t.error != null -> PanelAction("Back", Modifier.weight(1f)) { t.dismissError() }
                draft != null -> {
                    PanelAction(if (draft.page == EntryPage.CHOOSE) "Cancel" else "Back", Modifier.weight(1f)) {
                        when (draft.page) {
                            EntryPage.CHOOSE -> { t.cancelEdit(); pane = UpgradePane.PIECE }
                            EntryPage.STEP -> draft.page = EntryPage.VALUE
                            else -> draft.page = EntryPage.CHOOSE
                        }
                    }
                    if (draft.page == EntryPage.VALUE) PanelAction("Apply total", Modifier.weight(1.5f), pink = true) { draft.apply() }
                    else if (draft.page == EntryPage.REVIEW || draft.correction && draft.page == EntryPage.CHOOSE) PanelAction("Save +${draft.next}", Modifier.weight(1.5f), pink = true) {
                        if (draft.save(t)) pane = UpgradePane.PIECE
                    }
                }
                t.sellQueueComplete -> PanelAction("Undo mark",Modifier.weight(1.5f),pink=true,enabled=t.canUndoSold) { t.undoSold();pane=UpgradePane.PIECE }
                pane == UpgradePane.PIECE -> {
                    if(t.isSellQueue) {
                        PanelAction("Sold ›",Modifier.weight(1f),pink=true,enabled=t.canMarkSold,label="Sold and next") { t.markSold(item.id);pane=UpgradePane.PIECE }
                        PanelAction("Keep ›",Modifier.weight(1f),enabled=t.canMarkSold,label="Keep and next") { t.markSold(item.id, keep = true);pane=UpgradePane.PIECE }
                    }
                    if (item.enhance < 15 && UpgradeRecord.supported(item)) PanelAction("Record +${((item.enhance / 3 + 1) * 3).coerceAtMost(15)}", Modifier.weight(if(t.isSellQueue)1.2f else 1.7f), pink = !t.isSellQueue) { correction = false; t.beginEdit() }
                    // a finished piece: record the reforge, then equipping it, as done in the game
                    if (!t.isSellQueue && item.enhance == 15 && item.level == 85 && t.canReforge(item.id))
                        PanelAction("Reforge", Modifier.weight(1.2f), pink = t.checkRun.equipHeroes.isEmpty(), label = "Record reforge") { t.reforge() }
                    if (!t.isSellQueue && item.enhance == 15 && t.checkRun.equipHeroes.isNotEmpty() && t.equippedOn == null)
                        PanelAction("Equip", Modifier.weight(1.2f), pink = true, label = "Record equip") { pane = UpgradePane.EQUIP }
                }
                else -> {
                    PanelAction("Back", Modifier.weight(1f)) { pane = UpgradePane.PIECE }
                    if (pane == UpgradePane.HISTORY && r.history.size > 1) PanelAction("Undo last entry", Modifier.weight(1.5f)) { t.undo(); pane = UpgradePane.PIECE }
                }
            }
        }
    }
}

/** As tall as [natural] (the piece page drawn with room to spare) for every page of this piece, so the buttons below don't move. */
@Composable
private fun PanelBody(modifier: Modifier, floor: Dp, cap: Dp, need: Dp, extra: Dp, natural: @Composable () -> Unit, content: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val width = c.maxWidth
        val measured = subcompose("natural") { Box(Modifier.padding(horizontal = 2.dp)) { natural() } }.maxOfOrNull { it.measure(Constraints(maxWidth = width)).height } ?: 0
        val capPx = cap.roundToPx()
        val height = maxOf(measured.coerceIn(minOf(floor.roundToPx(), capPx), capPx), minOf(need.roundToPx(), capPx)) + extra.roundToPx()
        val placeables = subcompose("body", content).map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

private fun chanceBadge(t: TrackerFeature) = if (!t.hasTarget && t.checkRun.results.isNotEmpty() && !t.checkRun.busy && t.scoreQuery == null)
    when { t.equippedOn != null -> "Done"; t.record?.item?.enhance == 15 -> if (t.checkRun.equipHeroes.isNotEmpty()) "Equip" else "None"; else -> t.checkRun.chance?.let { "$it%" } ?: "Advice" }
    else if(t.sellQueueComplete)"Done" else if(t.isRollReview)if(t.reviewProtected)"Guard" else "Review" else t.goalOutcome?.let { when { !it.supported -> "Unknown"; t.scoreGoal!=null -> if(it.matchesNow) "Above" else if(it.rareOnly) "Rare" else if(!it.possible) "0%" else it.label(); it.matchesNow -> "Ready"; it.rareOnly -> "Rare"; !it.possible -> "Stop"; else -> it.label() } } ?: if (t.checkRun.busy) "Busy" else "Advice"

@Composable
private fun PieceSummary(t: TrackerFeature, item: Item, height: Float, es: EsForecast.Es?, measure: Boolean = false) {
    if (height < 60 * LocalDensity.current.fontScale + 12) {
        PanelTextPages("${item.rank} Lv.${item.level} · ${item.mainType.short} ${item.mainType.format(item.mainValue)}\n" +
            item.subs.joinToString("\n") { "${it.type.short} ${it.type.format(it.value)}" } +
            if (!t.hasTarget||t.isRollReview) "\n${t.title}" + (weakestLine(t)?.let { "\n$it" } ?: "") else "", height)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().inset().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatIcon(item.mainType.stat, 13.dp, C.ink); Spacer(Modifier.width(4.dp))
            FitText(item.mainType.format(item.mainValue), zen(11.sp), Modifier.weight(1f))
            FitText(if(t.isRollReview)t.reviewRolls?.let { "${e7.gearscan.opt.RollReview.overallWeakCount(it)}/${it.size} low" }?:"History unknown" else "${item.rank} · Lv.${item.level}", zen(9.sp, color = C.muted))
        }
        GearSearchSubs(item, t.goal?.minimums?.keys ?: emptySet())
        }
        if (t.hasEs) {
            if (es != null) EsBlock(es, t.rollOn) else if (measure) EsBlockSpace()
        }
        if (!t.hasTarget && !t.hasEs) FitText(t.title, zen(10.sp, color = C.blue))
        // the measuring copy keeps a line for advice that arrives after the check, so the panel doesn't grow then
        if (!t.hasTarget) (weakestLine(t) ?: " ".takeIf { measure && item.enhance < 15 })?.let { FitText(it, zen(9.sp, color = C.muted)) }
    }
}

@Composable
private fun EsBlock(e: EsForecast.Es, rollOn: Double) {
    Column(Modifier.fillMaxWidth().inset().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText("ES ${e.now}${e.typical?.takeIf { e.finishShare > 0 }?.let { " → ${it.toInt()}" } ?: ""}", dela(13.sp), Modifier.weight(1f))
            EsChip(e, rollOn)
        }
        EsBar(e)
    }
}

/** The room [EsBlock] takes, for the measuring copy while the forecast is still being worked out. */
@Composable
private fun EsBlockSpace() {
    val clear = Color.Transparent
    Column(Modifier.fillMaxWidth().inset().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText("ES", dela(13.sp, clear), Modifier.weight(1f))
            Box(Modifier.border(1.5.dp, clear, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) { Text("Rare only", style = zen(11.sp, color = clear)) }
        }
        Spacer(Modifier.fillMaxWidth().height(14.dp))
    }
}

@Composable
private fun DraftBody(d: UpgradeDraft, height: Float) {
    when (d.page) {
        EntryPage.CHOOSE -> {
            PanelChoices(d.choiceNames, height, if (d.reveal) "New fourth substat" else if (d.correction) "Correct totals · +${d.next}" else "Which stat increased?") { d.choose(it) }
        }
        EntryPage.STEP -> PanelChoices(d.stepOptions.map { fmtNum(it) }, height, "Change by") { i ->
            d.step = d.stepOptions[i]; d.page = EntryPage.VALUE
        }
        EntryPage.VALUE -> Column(verticalArrangement = Arrangement.spacedBy(if (height < 95) 2.dp else 3.dp)) {
            Row(Modifier.height(if (height < 95) 21.dp else 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FitText("${d.type.short} · ${fmtNum(d.bounds.minimum)}-${fmtNum(d.bounds.maximum)}", zen((if (height < 95) 10 else 11).sp), Modifier.weight(1f))
                if (d.bounds.maximum - d.bounds.minimum >= 10) PanelAction("Step ${fmtNum(d.step)}", Modifier.width(65.dp), label = "Choose step size") { d.page = EntryPage.STEP }
            }
            Row(Modifier.height(if (height < 95) 32.dp else 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelAction("−", Modifier.width(38.dp), label = "Decrease value") { d.input = (d.input - d.step).coerceIn(d.bounds.minimum, d.bounds.maximum) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FitText(fmtNum(d.input), dela((if (height < 95) 18 else 21).sp, C.blue)) }
                PanelAction("+", Modifier.width(38.dp), label = "Increase value") { d.input = (d.input + d.step).coerceIn(d.bounds.minimum, d.bounds.maximum) }
            }
            NumberRangeSlider(d.input, d.bounds.minimum, d.bounds.maximum, d.step, Modifier.fillMaxWidth().height(if (height < 95) 18.dp else 24.dp)) { d.input = it }
        }
        EntryPage.REVIEW -> if (height < 60 * LocalDensity.current.fontScale + 12) {
            PanelTextPages("Review +${d.next}\n${d.item.mainType.short} ${d.item.mainType.format(d.main)} · automatic\n" +
                d.preview().subs.joinToString("\n") { "${it.type.short} ${it.type.format(it.value)}" }, height)
        } else Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FitText("Review +${d.next} · ${d.item.mainType.short} ${d.item.mainType.format(d.main)}", zen(11.sp))
            GearSearchSubs(d.preview())
            FitText("Main stat is automatic", zen(9.sp, color = C.muted))
        }
    }
}

@Composable
private fun QueuePage(g: AppGraph, height: Float, selected: () -> Unit) {
    val t = g.tracker
    val scale = LocalDensity.current.fontScale
    val perPage = floor((height - 30) / (42 * scale.coerceAtLeast(1f))).toInt().coerceAtLeast(1)
    var page by remember(t.pendingQueue, perPage) { mutableIntStateOf(t.queueIndex.coerceAtLeast(0) / perPage) }
    val pages = ((t.pendingQueue.size + perPage - 1) / perPage).coerceAtLeast(1)
    page = page.coerceIn(0, pages - 1)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            t.pendingQueue.drop(page * perPage).take(perPage).forEachIndexed { offset, entry ->
                val item = g.records.records[entry.id]?.item
                if (item != null) Row(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(9.dp)).background(if (entry.id == t.selected) C.lightPink else C.field)
                    .border(1.dp, C.line, RoundedCornerShape(9.dp)).tap { t.selectInQueue(page * perPage + offset); selected() }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GearTile(item, 28.dp, setIcon = entry.setIcon)
                    Column(Modifier.weight(1f)) {
                        FitText("${entry.setName} ${item.slot.label}", zen(10.sp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            FitText("#${page * perPage + offset + 1}", zen(8.sp, color = C.muted), Modifier.weight(1f))
                            val out = entry.scoreGoal?.let { e7.gearscan.opt.GearScore.forecast(item,it).outcome(it.floor) }?:entry.goal?.let { GearPotential.forecast(item,it) }
                            FitText(if(t.isRollReview)"Review" else if(out==null||!out.supported) "Unknown" else if (out.rareOnly) "Rare" else if (!out.possible) if(entry.scoreGoal==null) "Stop" else "0%" else out.label(), zen(9.sp, color = C.blue))
                        }
                    }
                }
            }
        }
        PanelPager(page, pages, "${page * perPage + 1}-${min((page + 1) * perPage, t.pendingQueue.size)} of ${t.pendingQueue.size}", "list") { page = it }
    }
}

@Composable
private fun PanelChoices(names: List<String>, height: Float, title: String, select: (Int) -> Unit) {
    val scale = LocalDensity.current.fontScale
    val columns = choiceColumns(names.size)
    val rows = floor((height - (if (title.isEmpty()) 8 else 55)) / 34).toInt().coerceIn(1, 3)
    val count = rows * columns
    var page by remember(names, count) { mutableIntStateOf(0) }
    val pages = ((names.size + count - 1) / count).coerceAtLeast(1)
    val titleHeight = (12.6f * scale).coerceAtLeast(16f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title.isNotEmpty()) FitText(title, zen(9.sp), Modifier.height(titleHeight.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val shown = names.indices.drop(page * count).take(count)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = (maxWidth - 4.dp * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0 until (shown.size + columns - 1) / columns) Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                        shown.drop(r * columns).take(columns).forEach { index -> PanelAction(names[index], Modifier.width(cell)) { select(index) } }
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
        val rolls=t.reviewRolls
        PanelTextPages("${t.title}\n\n"+(rolls?.joinToString("\n") { r->"${if(r.enhance==0)"Start" else "+${r.enhance}${if(r.reveal)" new" else ""}"}: ${r.stat.short} +${r.stat.format(r.value)} (usual ${r.stat.format(r.range.first.toDouble())}–${r.stat.format(r.range.last.toDouble())})" }?:"Roll history unavailable")+"\n\n${t.reviewStatText}\n\nEach stat limit is separate. Review the actual substat totals before selling. Recorded upgrades update this history.",height)
        return
    }
    t.scoreGoal?.let { goal->
        val item=t.record!!.item
        PanelTextPages("${goal.label()}\n${goal.referenceLabel()}\n\nNow: ${fmtNum(goal.model.value(item))} pts\nDiversity penalty: ${goal.model.diversity}%\nMain contribution: ${goal.model.mainPercent}%\n\n${t.title}\n\n${if(goal.reforge) "Includes eligible reforging" else "Before reforging"}. This is a score filter, not a sell verdict.",height)
        return
    }
    val goal = t.goal ?: return
    val item = t.record!!.item
    val finish = if (goal.reforge && goal.reforgeAllowed && item.level == 85) "At +15 & reforged" else "At +15"
    PanelTextPages(finish + "\n\n" + goal.minimums.entries.joinToString("\n") { (stat, wanted) ->
        "${stat.short}: ${stat.format(item.subs.firstOrNull { it.type == stat }?.value ?: 0.0)} / ${stat.format(wanted)}"
    } + "\n\n${t.title}\nChance is for every target together.", height)
}

@Composable
private fun HistoryPage(r: UpgradeRecord, height: Float, highlight: Set<GearStat>) {
    PanelTextPages(r.history.mapIndexed { i, item ->
        "${if (i == 0) "Scan" else "Step $i"} · +${item.enhance} · ${item.mainType.short} ${item.mainType.format(item.mainValue)}\n" +
            item.subs.joinToString(" · ") { "${it.type.short} ${it.type.format(it.value)}" }
    }.joinToString("\n\n"), height)
}

/** Long notices/help/advice are split at rendered line boundaries for this exact body size and font scale. */
@Composable
private fun PanelTextPages(text: String, height: Float) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = zen(11.sp, color = C.muted)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val available = with(density) { (maxHeight - 31.dp).roundToPx() }.coerceAtLeast(1)
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

private fun helpText(t: TrackerFeature) = listOfNotNull(t.notice,
    "For Sell review lists, sell in Epic Seven first, then tap Sold › to mark it here; Keep › keeps a piece out of Sell review until you unlock it. Undo restores only the helper mark.\n\nRecord the totals after the next +3. The main stat is filled automatically. Each piece keeps its own history.",
    if(t.isRollReview) "This is the roll-review list. Low upgrade values alone do not mean a piece should be sold. Review individual stats. New recorded upgrades are appended to the roll history; recompute the sell-filter list to apply its safeguards again."
    else if(t.scoreGoal!=null) "The chance is for reaching the comparison points from your applied sell filters. These use your equipped gear or a custom score. Recorded upgrades refresh the comparison and chances. A filter match does not mean a piece is useless or should be sold. Rare endpoints have no estimated probability."
    else if (t.hasTarget) "The chance is for all targets together, using usual roll ranges. Rare maximum rolls have no estimated percentage. No modification stones are assumed."
    else "Advice compares complete builds for saved hero setups. The bar is the better of each hero's best build without this piece and with it as it is now. The chance is the share of ${e7.gearscan.features.tracker.PIECE_SIMS} simulated finishes from here that beat it; \"from\" lists the weakest finishes that still do, as substats at +15 before reforging. Pause means stop spending for now; it does not mean sell. After +15, Reforge and Equip record what you did in the game; Equip writes the piece into this app's gear until your next scan.",
    "Rescan after selling or reforging. ~ marks an estimate until a fresh scan supplies exact experience tie-breaks.").joinToString("\n\n")

/** The weakest finish that still helps the hero it helps most often, for the piece page. */
private fun weakestLine(t: TrackerFeature): String? {
    if (t.checkRun.busy || t.record?.item?.enhance == 15) return null
    t.checkRun.betterBet?.let { return "Better bet: ${it.second}" }
    val h = t.checkRun.results.filter { it.hits.isNotEmpty() }.maxByOrNull { it.hits.size } ?: return null
    return if (h.hits.size == h.total) "An upgrade whatever the rolls" else h.minimal.firstOrNull()?.let { "Upgrade at +15 from: $it" }
}

/** The final build for the hero the piece helps most (or can be equipped on), as lines. */
private fun buildText(t: TrackerFeature): String {
    val h = t.checkRun.equipHeroes.firstOrNull()?.takeIf { t.record?.item?.enhance == 15 }
        ?: t.checkRun.results.filter { it.build.isNotEmpty() && (it.hits.isNotEmpty() || it.current) }.maxByOrNull { it.hits.size } ?: return "No build to show yet."
    val with = if (t.record?.item?.enhance == 15 || h.hits.isEmpty()) "this piece as it is" else "a usual finish of this piece"
    return "Final build for ${h.name}, with $with" + (if (h.typical.isNotEmpty() && h.hits.isNotEmpty()) " (usually ${h.typical})" else if (h.nowGain.isNotEmpty()) " (${h.nowGain})" else "") +
        "\n\n" + h.build.joinToString("\n") + if (h.changes.isNotEmpty()) "\n\nBest finish: ${h.changes}" else ""
}

private fun adviceText(t: TrackerFeature) = buildList {
    add(t.title)
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
    if (t.checkRun.expected > 0 && !t.checkRun.busy && t.checkRun.uncertain) add("Some searches are unfinished. No stop decision is certain; use Recheck builds to continue.")
}.joinToString("\n\n")

@Composable
private fun PanelPager(page: Int, pages: Int, text: String, kind: String, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelAction("‹", Modifier.width(32.dp), enabled = page > 0, label = "Previous $kind page") { change(page - 1) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { FitText(text, zen(9.sp, color = C.muted)) }
        PanelAction("›", Modifier.width(32.dp), enabled = page + 1 < pages, label = "Next $kind page") { change(page + 1) }
    }
}

@Composable
private fun PanelAction(text: String, modifier: Modifier = Modifier, pink: Boolean = false, enabled: Boolean = true, label: String = text, click: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 27.dp).fillMaxHeight().then(if (enabled) Modifier.padding(bottom = 2.dp).hardShadow(C.edge, 0.dp, 2.dp, 8.dp) else Modifier)
        .clip(shape).background(if (!enabled) C.paper else if (pink) C.pink else C.card)
        .border(1.5.dp, if (pink || enabled) C.edge else C.line, shape).tap(if (enabled) click else null).semantics { contentDescription = label }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        FitText(text, zen(10.sp, color = if (!enabled) C.faint else if (pink) C.onAccent else C.ink))
    }
}

/** A recessed block inside the panel: field fill and a thin rule, so sections read as layers. */
private fun Modifier.inset(): Modifier = this.clip(RoundedCornerShape(10.dp)).background(C.field).border(1.dp, C.line, RoundedCornerShape(10.dp))
