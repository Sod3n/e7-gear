package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.InventoryOrder
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.SellSort
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.EsForecast
import e7.gearscan.opt.GearPotential
import e7.gearscan.opt.GearScore
import e7.gearscan.opt.RollReview
import e7.gearscan.opt.SellSearch
import e7.gearscan.opt.UpgradeCheck
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.ui.Outcome
import org.json.JSONArray
import org.json.JSONObject

/** Simulated finishes the tracker checks a piece with: the chance it ends up an upgrade, and its weakest helping finishes. */
const val PIECE_SIMS = 120

class TrackerFeature(g: AppGraph) : Feature(g, "tracker") {
    data class QueueEntry(
        val id: Long,
        val goal: GearPotential.Goal?,
        val setName: String,
        val setIcon: String?,
        val scoreGoal: GearScore.Goal? = null,
        val reviewRolls: List<RollReview.Roll>? = null,
        val reviewHistory: Int = 1,
        val reviewAllowed: Boolean = false,
        val reviewCoverage: Boolean = false,
    ) {
        fun toJson() =
            JSONObject().put("id", id).put("goal", goal?.toJson() ?: JSONObject.NULL).put("scoreGoal", scoreGoal?.toJson() ?: JSONObject.NULL)
                .put("reviewRolls", RollReview.rollsJson(reviewRolls)).put("reviewHistory", reviewHistory).put("reviewAllowed", reviewAllowed)
                .put("reviewCoverage", reviewCoverage).put("setName", setName).put("setIcon", setIcon ?: JSONObject.NULL)
    }

    private class EquipUndo(val itemId: Long, val before: JSONObject, val owner: Long?, val displaced: Item?, val heroId: Long)

    val upgrades = savedFile("upgrades.json", backup = true, path = "upgrades.json")
    val check = work<Unit>("Upgrade check", Weight.Yielding, foreground = true, notice = "Checking this upgrade")
    val checkRun = UpgradeCheckRun(this, g, check)

    var shown by mutableStateOf(false)
        private set
    private var records: Map<Long, UpgradeRecord>
        get() = g.records.records
        set(value) {
            g.records.records = value
        }
    var selected: Long? by mutableStateOf(null)
        private set
    var queue: List<QueueEntry> by mutableStateOf(emptyList())
        private set
    var queueBasis: String by mutableStateOf(RollReview.BASIS)
        private set

    // the list the queue came from, for the way back to it: "heroes" (Improve heroes), else Gear potential or Sell filters
    var queueSource: String by mutableStateOf("")
        private set
    var queueScan: Long by mutableStateOf(0)
        private set
    var scoreQuery: SellQuery? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        internal set
    var notice: String? by mutableStateOf(null)
        private set
    var goal: GearPotential.Goal? by mutableStateOf(null)
        private set
    var scoreGoal: GearScore.Goal? by mutableStateOf(null)
        private set
    var editing by mutableStateOf(false)
        internal set
    private var equipUndo: EquipUndo? by mutableStateOf(null)
    var equippedOn: String? by mutableStateOf(null)
        private set

    private val scan get() = g.scan.lastScan
    private val box get() = g.scan.box
    private val content get() = g.content.content

    val record get() = records[selected]
    val cleared: Set<Long> get() = g.marks.reviewed
    val pendingQueue get() = queue.filter { it.id !in cleared }
    val queueIndex get() = pendingQueue.indexOfFirst { it.id == selected }
    val isSellQueue get() = scoreQuery != null && queue.isNotEmpty()
    val sellQueueComplete get() = isSellQueue && pendingQueue.isEmpty()
    val needsRollRefresh get() = isSellQueue && queueBasis != RollReview.BASIS && (scoreQuery?.scoreFilter == false || scoreQuery?.sort == SellSort.QUALITY)
    val canUndoSold get() = g.marks.lastSale(scan, queue.mapTo(HashSet()) { it.id }) != null
    val canMarkSold get() = isSellQueue && !sellQueueComplete && !editing && selected in pendingQueue.map { it.id }
    val canUndoEquip get() = equipUndo != null
    val rollOn get() = g.sellReview.query.rollOn
    val rebuilds get() = g.improveHeroes.rebuilds
    val setIcon get() = record?.item?.let { content.set(it.set)?.icon }
    val setName get() = record?.item?.let { content.set(it.set)?.name }
    val hasEs get() = scoreQuery != null

    val isRollReview get() = scoreQuery != null && !scoreQuery!!.scoreFilter && scoreGoal == null && goal == null
    val reviewRolls get(): List<RollReview.Roll>? {
        val entry = queue.firstOrNull { it.id == selected } ?: return null
        var rolls = entry.reviewRolls
        val history = record?.history.orEmpty().drop((entry.reviewHistory - 1).coerceAtLeast(0))
        for ((a, b) in history.zipWithNext()) rolls = RollReview.append(rolls, a, b)
        return rolls
    }
    val reviewSafeguards get(): List<RollReview.Safeguard> {
        val item = record?.item ?: return emptyList()
        val q = scoreQuery ?: return emptyList()
        val e = queue.firstOrNull { it.id == selected } ?: return emptyList()
        return q.keepStats.map { (stat, value) -> RollReview.safeguard(item, stat, value, q.reforge, e.reviewAllowed, e.reviewCoverage) }
    }
    val reviewProtected get() = reviewSafeguards.any { it.active(scoreQuery?.keepChance ?: 0.0) }
    val reviewStatText get(): String {
        val item = record?.item ?: return ""
        val q = scoreQuery ?: return ""
        val e = queue.firstOrNull { it.id == selected } ?: return ""
        return reviewSafeguards.joinToString("\n") { g ->
            "${g.stat.short} ${g.stat.format(g.minimum)}: " +
                when {
                    g.now -> "already reached"
                    !g.known -> "future unknown"
                    g.chance > 0 -> GearPotential.percent(g.chance)
                    g.possible -> "rare possibility, odds unknown"
                    else -> "not reachable"
                }
        } + "\n\n" +
            item.subs.sortedBy { if (it.type == GearStat.SPD) 0 else 1 }.joinToString("\n") { sub ->
                val line = RollReview.line(item, sub.type, q.reforge, e.reviewAllowed, e.reviewCoverage)
                "${sub.type.short} ${sub.type.format(sub.value)} → " +
                    (line.maximum?.let { "up to ${sub.type.format(it)} at +15${if (q.reforge) " & eligible reforge" else ""}" } ?: "finish unknown")
            }
    }
    val hasTarget get() = goal != null || scoreGoal != null || isRollReview
    val goalOutcome get() =
        record?.item?.let { item ->
            scoreGoal?.let { GearScore.forecast(item, it).outcome(it.floor) } ?: goal?.let { GearPotential.forecast(item, it) }
        }
    val title get() =
        when {
            sellQueueComplete -> "List complete"
            isRollReview && reviewProtected -> "Stat safeguard reached"
            isRollReview -> reviewRolls?.let { rolls -> "${RollReview.overallWeakCount(rolls)} of ${rolls.size} rolls below midrange" } ?: "Upgrade history unavailable"
            scoreGoal != null ->
                goalOutcome?.let { out ->
                    when {
                        !out.supported -> "Catch-up chance unavailable"
                        out.matchesNow -> "Comparison reached"
                        record?.item?.enhance == 15 && out.chance == 1.0 -> "Reforge to catch up"
                        out.rareOnly -> "Needs rare rolls to catch up"
                        out.possible -> "${out.label()} chance to catch up"
                        else -> "Cannot reach comparison"
                    }
                } ?: "Choose a piece"
            goal != null ->
                goalOutcome?.let { out ->
                    when {
                        out.matchesNow -> "Targets reached"
                        record?.item?.enhance == 15 && out.chance == 1.0 -> "Reforge to meet targets"
                        out.rareOnly -> "Rare rolls needed"
                        out.possible -> "${out.label()} chance to meet targets"
                        else -> "Targets no longer reachable"
                    }
                } ?: "Choose a piece"
            checkRun.busy -> "Checking builds · ${checkRun.results.size}/${checkRun.expected}"
            error != null -> "Check needs attention"
            checkRun.expected < 0 -> "Ready to check saved setups"
            checkRun.expected == 0 -> "Add hero setups to get advice"
            equippedOn != null -> "Equipped on $equippedOn"
            record?.item?.enhance == 15 ->
                if (checkRun.equipHeroes.isNotEmpty()) {
                    "Finished · upgrade for ${checkRun.equipHeroes.first().name}"
                } else if (checkRun.hasPotential) {
                    "Finished · potential upgrade"
                } else {
                    "Finished · no upgrade found"
                }
            checkRun.hasFuture && checkRun.results.any { it.provisional && it.hits.isNotEmpty() } -> "Potential upgrade · check incomplete"
            checkRun.hasFuture ->
                (checkRun.chance?.let { "Continue · $it% chance to upgrade" } ?: "Continue · potential upgrade") +
                    (checkRun.results.filter { it.hits.isNotEmpty() }.maxByOrNull { it.hits.size }?.better?.size?.takeIf { it > 0 }?.let { " · $it better ${if (it == 1) "bet" else "bets"}" } ?: "")
            checkRun.uncertain -> "Undecided · check incomplete"
            checkRun.results.any { it.current } -> "Pause · useful as it is"
            else -> "Pause · no upgrade found"
        }

    init {
        runCatching { restore(JSONObject(upgrades.read()!!), save = false) }
            .onFailure { if (upgrades.exists) error = "Saved upgrade history could not be read. Your scan is unchanged." }
    }

    fun select(
        item: Item,
        scan: Long,
        keepQueue: Boolean = false,
    ) {
        if (!keepQueue || queue.none { it.id == item.id }) {
            queue = emptyList()
            scoreQuery = null
            queueScan = 0
        }
        checkRun.stopCheck()
        editing = false
        error = null
        if (selected != item.id || records[item.id]?.scan != scan) {
            goal = null
            scoreGoal = null
        }
        if (selected != item.id) {
            checkRun.results = emptyList()
            checkRun.signature = ""
            checkRun.expected = -1
        }
        selected = item.id
        queue.firstOrNull { it.id == item.id }?.let {
            goal = it.goal
            scoreGoal = it.scoreGoal
        }
        val old = records[item.id]
        if (old == null || old.scan != scan) {
            records = records + (item.id to UpgradeRecord(scan, listOf(UpgradeRecord.fromScan(item))))
            checkRun.results = emptyList()
            checkRun.signature = ""
            checkRun.expected = -1
            notice = if (old != null) "Started from your newer scan; the scan takes priority over manual entries." else null
        }
        persist()
    }

    fun startQueue(
        items: List<Pair<Item, QueueEntry>>,
        scan: Long = this.scan,
        scoreQuery: SellQuery? = null,
        source: String = "",
    ) {
        this.scoreQuery = scoreQuery
        queueScan = scan
        queueBasis = RollReview.BASIS
        queueSource = source
        val unique = items.distinctBy { it.first.id }
        if (unique.isEmpty()) return
        records = records +
            unique.associate { (item, _) ->
                item.id to (records[item.id]?.takeIf { it.scan == scan } ?: UpgradeRecord(scan, listOf(UpgradeRecord.fromScan(item))))
            }
        queue = unique.map { it.second }
        select(unique.first().first, scan, keepQueue = true)
    }

    fun useGoal(value: GearPotential.Goal?) {
        invalidate()
        goal = value
        scoreGoal = null
        scoreQuery = null
        persist()
    }

    fun useScoreGoal(value: GearScore.Goal?) {
        invalidate()
        scoreGoal = value
        goal = null
        queue = queue.map { if (it.id == selected) it.copy(scoreGoal = value, goal = null) else it }
        persist()
    }

    fun beginEdit() {
        error = null
        editing = true
    }

    fun cancelEdit() {
        editing = false
    }

    fun dismissError() {
        error = null
    }

    fun commit(
        main: Double,
        values: List<Double>,
        added: Pair<GearStat, Double>?,
        correction: Boolean,
    ): Boolean {
        val r = record ?: return false
        val next = if (correction) r.item.enhance else ((r.item.enhance / 3 + 1) * 3).coerceAtMost(15)
        return runCatching {
            val update = r.record(next, main, values, added, correction)
            records = records + (update.item.id to update)
            invalidate()
            editing = false
            persist()
            checkRun.runCheck()
            true
        }.getOrElse {
            error = it.message ?: "Could not record this step."
            false
        }
    }

    fun undo() {
        val r = record ?: return
        if (r.history.size <= 1) return
        records = records + (r.item.id to r.undo())
        invalidate()
        editing = false
        persist()
        checkRun.runCheck()
    }

    // records the game's reforge of the finished level 85 piece; Undo in History takes it back
    fun reforge(): Boolean {
        val r = record ?: return false
        return runCatching {
            records = records + (r.item.id to r.reforged())
            invalidate()
            editing = false
            persist()
            checkRun.runCheck()
            true
        }.getOrElse {
            error = it.message ?: "Could not record the reforge."
            false
        }
    }

    fun canReforge(id: Long) = box?.canReforge(id) == true

    fun selectPiece(id: Long) {
        val item = box?.itemsById?.get(id) ?: return
        if (!UpgradeRecord.supported(item)) return
        select(item, scan)
        configure()
    }

    fun selectInQueue(index: Int) {
        val entry = pendingQueue.getOrNull(index)
        val original = entry?.let { box?.itemsById?.get(it.id) }?.takeIf { it.id !in g.marks.cleared }
        if (queueScan != scan) {
            error = "Your scan changed. Refresh the matches in E7 Gear."
        } else if (original != null) {
            select(original, scan, keepQueue = true)
            configure()
        } else {
            error = "This piece is no longer in the inventory. Refresh the matches in E7 Gear."
        }
    }

    fun switchTo(id: Long) {
        selectPiece(id)
        checkRun.runCheck()
    }

    fun useRebuilds(on: Boolean) {
        g.improveHeroes.rebuilds = on
        g.scan.touch()
        checkRun.runCheck()
    }

    fun markSold(
        expectedId: Long,
        keep: Boolean = false,
    ) {
        val record = record ?: return
        if (editing || scoreQuery == null || selected != expectedId || expectedId in g.marks.reviewed) return
        if (queueScan != scan || record.scan != scan || box?.itemsById?.containsKey(expectedId) != true || queue.none { it.id == expectedId }) {
            error = "Your scan changed. Refresh the matches before marking this piece."
            return
        }
        val index = queueIndex.coerceAtLeast(0)
        g.marks.recordSold(expectedId, scan, keep)
        invalidate()
        g.store.saveNow()
        val next = pendingQueue.getOrNull(index) ?: pendingQueue.lastOrNull()
        next?.let { e -> box?.itemsById?.get(e.id)?.let { select(it, scan, keepQueue = true) } }
        configure()
    }

    fun undoSold() {
        if (editing) return
        val mark = g.marks.lastSale(scan, queue.map { it.id }.toSet()) ?: return
        val b = box
        if (b?.itemsById?.containsKey(mark.id) != true) {
            error = "That piece is no longer in your scan."
            return
        }
        g.marks.undoSale(mark)
        invalidate()
        g.store.saveNow()
        select(b.itemsById.getValue(mark.id), scan, keepQueue = true)
        configure()
    }

    // the piece as recorded, now worn by the hero: its stats and owner go into the box (a new scan replaces them)
    fun equip(heroId: Long) {
        val b = box ?: return
        val r = record ?: return
        if (r.scan != scan || b.itemsById[r.item.id] == null) {
            error = "Your scan changed. Choose the piece again."
            return
        }
        val owner = b.itemsById.getValue(r.item.id).equippedOn
        val displaced = b.gearOf(heroId)[r.item.slot]?.takeIf { it.id != r.item.id }
        val before = b.writeStats(r.item) ?: return
        val next = Box(b.json)
        next.itemsById[r.item.id]?.let { next.setEquipped(it, heroId) }
        displaced?.let { d -> next.itemsById[d.id]?.let { next.setEquipped(it, null) } }
        persistBox(next)
        equippedOn = g.heroes.heroName(heroId) ?: "the hero"
        equipUndo = EquipUndo(r.item.id, before, owner, displaced, heroId)
    }

    fun undoEquip() {
        val u = equipUndo ?: return
        box?.let { cur ->
            cur.restore(u.itemId, u.before)
            val back = Box(cur.json)
            back.itemsById[u.itemId]?.let { back.setEquipped(it, u.owner) }
            u.displaced?.let { d -> back.itemsById[d.id]?.let { back.setEquipped(it, u.heroId) } }
            persistBox(back)
        }
        equipUndo = null
        equippedOn = null
    }

    private fun persistBox(b: Box) {
        g.scan.replaceBox(b)
        g.scan.touch()
        g.scan.saveBox()
        g.store.saveNow()
    }

    fun returnToApp() {
        // in the phone's floating window the app is already here: show all of it
        if (g.overlay.floating) g.overlay.floatingPanel = false else g.platform.showApp()
    }

    fun track(
        item: Item,
        goal: GearPotential.Goal,
        openOverlay: Boolean,
    ): Outcome {
        checkRun.stopCheck()
        select(item, scan)
        configure()
        useGoal(goal)
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) openOverlay() else Outcome.Done
    }

    fun trackQueue(
        entries: List<Pair<Item, QueueEntry>>,
        openOverlay: Boolean,
    ): Outcome {
        checkRun.stopCheck()
        startQueue(entries, scan)
        configure()
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) openOverlay() else Outcome.Done
    }

    fun trackSellQueue(
        rows: List<Pair<Item, SellSearch.Row>>,
        query: SellQuery,
        openOverlay: Boolean,
    ): Outcome {
        val entries =
            rows.map { (original, row) ->
                val set = content.set(original.set)
                original to
                    QueueEntry(
                        original.id, null, set?.name ?: original.set.removeSuffix("Set"), set?.icon,
                        row.goal.takeIf { query.scoreFilter }, row.rolls, records[original.id]?.takeIf { it.scan == scan }?.history?.size ?: 1,
                        row.goal.reforgeAllowed, row.goal.supported,
                    )
            }
        startQueue(entries, scan, query)
        configure()
        g.nav.go(Screen.Upgrade)
        return if (openOverlay) openOverlay() else Outcome.Done
    }

    fun trackPiece(id: Long): Outcome {
        val original = box?.itemsById?.get(id)?.takeIf { it.id !in g.marks.cleared && UpgradeRecord.supported(it) } ?: return Outcome.Done
        select(original, scan)
        configure()
        g.nav.go(Screen.Upgrade)
        return openOverlay()
    }

    fun trackPieces(ids: List<Long>): Outcome {
        val b = box ?: return Outcome.Done
        val items =
            ids.distinct().mapNotNull { id ->
                val o = b.itemsById[id]?.takeIf { it.id !in g.marks.cleared && UpgradeRecord.supported(it) } ?: return@mapNotNull null
                val set = content.set(o.set)
                o to QueueEntry(o.id, null, set?.name ?: o.set.removeSuffix("Set"), set?.icon)
            }
        if (items.isEmpty()) return Outcome.Done
        startQueue(items, scan, source = "heroes")
        configure()
        g.nav.go(Screen.Upgrade)
        return openOverlay()
    }

    fun openOverlay(): Outcome {
        val r = record ?: return Outcome.Done
        if (r.scan != scan || r.item.id !in (box?.itemsById ?: emptyMap())) return Outcome.Failed("Your scan changed. Choose the gear again.")
        configure()
        show()
        g.overlay.expanded = false
        g.overGame.open { checkRun.runCheck() }
        return Outcome.Done
    }

    fun show() {
        shown = true
    }

    fun hide() {
        shown = false
    }

    fun esFor(item: Item): EsForecast.Es? {
        val q = scoreQuery ?: return null
        val b = box
        val ctx = g.sellReview.context(q)
        if (b == null || ctx == null) return null
        return EsForecast.of(
            item,
            InventoryOrder.score(item, if (item.enhance == b.itemsById[item.id]?.enhance) ctx.raw[item.id] else null),
            ctx.esBar(item.slot), q.reforge, b.canReforge(item.id), ctx.coverage(item), q.forecastMs,
        )
    }

    fun configure() {
        syncMarks()
        refreshScoreGoal()
    }

    // a piece of a sell list marked elsewhere is passed over
    override fun onBoxAdopted(box: Box?) {
        syncMarks()
    }

    override fun onRestored() {
        reload()
        syncMarks()
    }

    override fun onErased() {
        reset()
    }

    fun syncMarks() {
        if (isSellQueue && queueScan == scan && selected in g.marks.reviewed) {
            pendingQueue.firstOrNull()?.let { e ->
                box?.itemsById?.get(e.id)?.let { item -> if (records[e.id]?.scan == scan) select(item, scan, keepQueue = true) }
            }
        }
    }

    internal fun refreshScoreGoal() {
        val q = scoreQuery?.takeIf { it.scoreFilter } ?: return
        val context = g.sellReview.context(q) ?: return
        val ids = queue.map { it.id }.toSet() + listOfNotNull(selected)
        refreshScoreGoals(context.items.filter { it.id in ids }.associate { it.id to context.goal(it) })
    }

    private fun refreshScoreGoals(goals: Map<Long, GearScore.Goal>) {
        val next = queue.map { e -> goals[e.id]?.let { e.copy(goal = null, scoreGoal = it) } ?: e }
        val selectedGoal = goals[selected] ?: scoreGoal
        if (next != queue || selectedGoal != scoreGoal) {
            invalidate()
            queue = next
            scoreGoal = selectedGoal
            goal = null
            persist()
        }
    }

    fun invalidate() {
        checkRun.clear()
        error = null
        equipUndo = null
        equippedOn = null
    }

    fun toJson() =
        JSONObject().put("queueBasis", queueBasis).put("queueSource", queueSource).put("queueScan", queueScan)
            .put("scoreGoal", scoreGoal?.toJson() ?: JSONObject.NULL).put("scoreQuery", scoreQuery?.toJson() ?: JSONObject.NULL)
            .put("queue", JSONArray(queue.map { it.toJson() })).put("goal", goal?.toJson() ?: JSONObject.NULL)
            .put("records", JSONArray(records.values.map { it.toJson() })).put("selected", selected ?: JSONObject.NULL)
            .put("signature", checkRun.signature).put("attempt", checkRun.attempt).put("expected", checkRun.expected).put("results", JSONArray(checkRun.results.map { it.toJson() }))

    fun restore(
        j: JSONObject,
        save: Boolean = true,
    ) {
        checkRun.stopCheck()
        editing = false
        val a = j.optJSONArray("records") ?: JSONArray()
        records = (0 until a.length()).map { UpgradeRecord.fromJson(a.getJSONObject(it)) }.associateBy { it.item.id }
        selected = if (j.isNull("selected")) null else j.optLong("selected").takeIf { it in records }
        scoreGoal = j.optJSONObject("scoreGoal")?.let { runCatching { GearScore.Goal.of(it) }.getOrNull() }
        scoreQuery = j.optJSONObject("scoreQuery")?.let { runCatching { SellQuery.of(it).copy(scoreFilter = it.optBoolean("scoreFilter", true)) }.getOrNull() }
        goal = j.optJSONObject("goal")?.let { runCatching { GearPotential.Goal.of(it) }.getOrNull() }
        val q = j.optJSONArray("queue") ?: JSONArray()
        queue =
            (0 until q.length()).mapNotNull { i ->
                runCatching {
                    val e = q.getJSONObject(i)
                    QueueEntry(
                        e.getLong("id"), e.optJSONObject("goal")?.let(GearPotential.Goal::of), e.optString("setName"),
                        if (e.isNull("setIcon")) null else e.optString("setIcon"), e.optJSONObject("scoreGoal")?.let(GearScore.Goal::of),
                        RollReview.rollsOf(e.optJSONArray("reviewRolls")), e.optInt("reviewHistory", 1), e.optBoolean("reviewAllowed", false),
                        e.optBoolean("reviewCoverage", false),
                    )
                }.getOrNull()?.takeIf { it.id in records }
            }.distinctBy { it.id }
        queueBasis = j.optString("queueBasis", j.optJSONObject("scoreQuery")?.optString("rollBasis", "UPGRADES") ?: RollReview.BASIS)
        queueSource = j.optString("queueSource")
        queueScan = j.optLong("queueScan", queue.mapNotNull { records[it.id]?.scan }.minOrNull() ?: 0L)
        checkRun.signature = j.optString("signature")
        checkRun.attempt = j.optInt("attempt")
        checkRun.expected = j.optInt("expected", -1)
        val r = j.optJSONArray("results") ?: JSONArray()
        checkRun.results = (0 until r.length()).map { UpgradeCheck.HeroResult.of(r.getJSONObject(it)) }
        if (save) persist()
    }

    // after a restore from a backup the file holds the restored tracker
    fun reload() = restore(runCatching { JSONObject(upgrades.read().orEmpty()) }.getOrDefault(JSONObject()), save = false)

    fun reset() {
        shown = false
        restore(JSONObject(), save = false)
    }

    internal fun persist() = upgrades.write(toJson().toString())
}
