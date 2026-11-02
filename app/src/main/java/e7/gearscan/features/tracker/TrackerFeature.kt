package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.features.sellreview.SellReviewFeature
import e7.gearscan.features.improveheroes.ImproveHeroesFeature
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.SellSort
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearPotential
import e7.gearscan.opt.GearScore
import e7.gearscan.opt.RollReview
import e7.gearscan.opt.UpgradeCheck
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.ui.Outcome
import org.json.JSONArray
import org.json.JSONObject

/** Simulated finishes the tracker checks a piece with: the chance it ends up an upgrade, and its weakest helping finishes. */
const val PIECE_SIMS = 120

class TrackerFeature(
    ctx: FeatureContext,
    private val contentFeature: ContentFeature,
    private val scanFeature: ScanFeature,
    private val marks: MarksFeature,
    private val recordsFeature: RecordsFeature,
    heroes: HeroesFeature,
    pool: PoolFeature,
    private val sellReview: SellReviewFeature,
    private val improveHeroes: ImproveHeroesFeature,
    private val launcher: OverGameLauncher,
) : Feature(ctx, "tracker") {
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

    val upgrades = savedFile("upgrades.json", backup = true, path = "upgrades.json")
    val check = work<Unit>("Upgrade check", Weight.Yielding, foreground = true, notice = "Checking this upgrade")
    val checkRun = UpgradeCheckRun(this, ctx, check, scanFeature, recordsFeature, heroes, pool, marks, improveHeroes, contentFeature)
    val worn = WornPiece(this, ctx, scanFeature, heroes)
    val readout = UpgradeReadout(this, scanFeature, sellReview)
    val starts = TrackStarts(this, ctx, scanFeature, contentFeature, marks, recordsFeature)

    var shown by mutableStateOf(false)
        private set
    private var records: Map<Long, UpgradeRecord>
        get() = recordsFeature.records
        set(value) {
            recordsFeature.records = value
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

    private val scan get() = scanFeature.lastScan
    private val box get() = scanFeature.box
    private val content get() = contentFeature.content

    val record get() = records[selected]
    val cleared: Set<Long> get() = marks.reviewed
    val pendingQueue get() = queue.filter { it.id !in cleared }
    val queueIndex get() = pendingQueue.indexOfFirst { it.id == selected }
    val isSellQueue get() = scoreQuery != null && queue.isNotEmpty()
    val sellQueueComplete get() = isSellQueue && pendingQueue.isEmpty()
    val needsRollRefresh get() = isSellQueue && queueBasis != RollReview.BASIS && (scoreQuery?.scoreFilter == false || scoreQuery?.sort == SellSort.QUALITY)
    val canUndoSold get() = marks.lastSale(scan, queue.mapTo(HashSet()) { it.id }) != null
    val canMarkSold get() = isSellQueue && !sellQueueComplete && !editing && selected in pendingQueue.map { it.id }
    val rollOn get() = sellReview.query.rollOn
    val rebuilds get() = improveHeroes.rebuilds
    val setIcon get() = record?.item?.let { content.set(it.set)?.icon }
    val setName get() = record?.item?.let { content.set(it.set)?.name }
    val hasEs get() = scoreQuery != null

    val isRollReview get() = scoreQuery != null && !scoreQuery!!.scoreFilter && scoreGoal == null && goal == null
    val hasTarget get() = goal != null || scoreGoal != null || isRollReview
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
        val original = entry?.let { box?.itemsById?.get(it.id) }?.takeIf { it.id !in marks.cleared }
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
        improveHeroes.rebuilds = on
        ctx.rev.bump()
        checkRun.runCheck()
    }

    fun markSold(
        expectedId: Long,
        keep: Boolean = false,
    ) {
        val record = record ?: return
        if (editing || scoreQuery == null || selected != expectedId || expectedId in marks.reviewed) return
        if (queueScan != scan || record.scan != scan || box?.itemsById?.containsKey(expectedId) != true || queue.none { it.id == expectedId }) {
            error = "Your scan changed. Refresh the matches before marking this piece."
            return
        }
        val index = queueIndex.coerceAtLeast(0)
        marks.recordSold(expectedId, scan, keep)
        invalidate()
        ctx.store.saveNow()
        val next = pendingQueue.getOrNull(index) ?: pendingQueue.lastOrNull()
        next?.let { e -> box?.itemsById?.get(e.id)?.let { select(it, scan, keepQueue = true) } }
        configure()
    }

    fun undoSold() {
        if (editing) return
        val mark = marks.lastSale(scan, queue.map { it.id }.toSet()) ?: return
        val b = box
        if (b?.itemsById?.containsKey(mark.id) != true) {
            error = "That piece is no longer in your scan."
            return
        }
        marks.undoSale(mark)
        invalidate()
        ctx.store.saveNow()
        select(b.itemsById.getValue(mark.id), scan, keepQueue = true)
        configure()
    }

    fun returnToApp() {
        // in the phone's floating window the app is already here: show all of it
        if (ctx.overlay.floating) ctx.overlay.floatingPanel = false else ctx.platform.showApp()
    }

    fun openOverlay(): Outcome {
        val r = record ?: return Outcome.Done
        if (r.scan != scan || r.item.id !in (box?.itemsById ?: emptyMap())) return Outcome.Failed("Your scan changed. Choose the gear again.")
        configure()
        show()
        ctx.overlay.expanded = false
        launcher.open { checkRun.runCheck() }
        return Outcome.Done
    }

    fun show() {
        shown = true
    }

    fun hide() {
        shown = false
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
        if (isSellQueue && queueScan == scan && selected in marks.reviewed) {
            pendingQueue.firstOrNull()?.let { e ->
                box?.itemsById?.get(e.id)?.let { item -> if (records[e.id]?.scan == scan) select(item, scan, keepQueue = true) }
            }
        }
    }

    internal fun refreshScoreGoal() {
        val q = scoreQuery?.takeIf { it.scoreFilter } ?: return
        val context = sellReview.context(q) ?: return
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
        worn.clear()
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
