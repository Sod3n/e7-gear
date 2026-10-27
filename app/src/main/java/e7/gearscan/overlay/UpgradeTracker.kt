package e7.gearscan.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.data.*
import e7.gearscan.opt.UpgradeCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Own file: observations and completed hero checks are atomic, synchronous checkpoints. */
class UpgradeTracker(private val file: File) {
    var records: Map<Long, UpgradeRecord> by mutableStateOf(emptyMap()); private set
    var selected: Long? by mutableStateOf(null); private set
    val record get() = records[selected]
    data class QueueEntry(val id: Long, val goal: e7.gearscan.opt.GearPotential.Goal?, val setName: String, val setIcon: String?, val scoreGoal:e7.gearscan.opt.GearScore.Goal?=null,val reviewRolls:List<e7.gearscan.opt.RollReview.Roll>?=null,val reviewHistory:Int=1,val reviewAllowed:Boolean=false,val reviewCoverage:Boolean=false) {
        fun toJson() = JSONObject().put("id", id).put("goal", goal?.toJson()?:JSONObject.NULL).put("scoreGoal",scoreGoal?.toJson()?:JSONObject.NULL).put("reviewRolls",e7.gearscan.opt.RollReview.rollsJson(reviewRolls)).put("reviewHistory",reviewHistory).put("reviewAllowed",reviewAllowed).put("reviewCoverage",reviewCoverage).put("setName", setName).put("setIcon", setIcon ?: JSONObject.NULL)
    }
    var queue: List<QueueEntry> by mutableStateOf(emptyList()); private set
    var queueBasis:String by mutableStateOf(e7.gearscan.opt.RollReview.BASIS);private set
    /** The list the queue came from, for the way back to it: "heroes" (Improve heroes), else Gear potential or Sell filters. */
    var queueSource: String by mutableStateOf(""); private set
    val needsRollRefresh get()=isSellQueue&&queueBasis!=e7.gearscan.opt.RollReview.BASIS&&(scoreQuery?.scoreFilter==false||scoreQuery?.sort==SellSort.QUALITY)
    var queueScan:Long by mutableStateOf(0);private set
    var cleared: Set<Long> by mutableStateOf(emptySet())
    val pendingQueue get()=queue.filter { it.id !in cleared }
    val queueIndex get() = pendingQueue.indexOfFirst { it.id == selected }
    val isSellQueue get()=scoreQuery!=null&&queue.isNotEmpty()
    val sellQueueComplete get()=isSellQueue&&pendingQueue.isEmpty()
    var markSold:((Long)->Unit)?=null
    var undoSold:(()->Unit)?=null
    var canUndoSold by mutableStateOf(false)
    val canMarkSold get()=isSellQueue&&!sellQueueComplete&&!editing&&selected in pendingQueue.map { it.id }&&markSold!=null
    var selectQueue: ((Int) -> Unit)? = null
    var returnToApp: (() -> Unit)? = null
    var positionFor: ((Long) -> String?)? = null

    var scoreQuery: SellQuery? by mutableStateOf(null);private set
    fun startQueue(items: List<Pair<Item, QueueEntry>>, scan: Long, scoreQuery:SellQuery?=null, source: String = "") {
        this.scoreQuery=scoreQuery;queueScan=scan;queueBasis=e7.gearscan.opt.RollReview.BASIS;queueSource=source
        val unique = items.distinctBy { it.first.id }
        if (unique.isEmpty()) return
        records = records + unique.associate { (item, _) -> item.id to
            (records[item.id]?.takeIf { it.scan == scan } ?: UpgradeRecord(scan, listOf(UpgradeRecord.fromScan(item)))) }
        queue = unique.map { it.second }
        select(unique.first().first, scan, keepQueue = true)
    }

    var results: List<UpgradeCheck.HeroResult> by mutableStateOf(emptyList()); private set
    var signature = ""; private set
    var attempt = 0; private set
    var expected by mutableStateOf(-1)
    var busy by mutableStateOf(false)
    var error: String? by mutableStateOf(null)
    var notice: String? by mutableStateOf(null)
    var requestCheck: (() -> Unit)? = null
    var setIcon: String? by mutableStateOf(null)
    var setName: String? by mutableStateOf(null)
    var inventoryPosition: String? by mutableStateOf(null)
    var goal: e7.gearscan.opt.GearPotential.Goal? by mutableStateOf(null); private set
    var scoreGoal: e7.gearscan.opt.GearScore.Goal? by mutableStateOf(null); private set
    val isRollReview get()=scoreQuery!=null&&!scoreQuery!!.scoreFilter&&scoreGoal==null&&goal==null
    val reviewRolls get():List<e7.gearscan.opt.RollReview.Roll>? {
        val entry=queue.firstOrNull { it.id==selected }?:return null
        var rolls=entry.reviewRolls
        val history=record?.history.orEmpty().drop((entry.reviewHistory-1).coerceAtLeast(0))
        for((a,b) in history.zipWithNext())rolls=e7.gearscan.opt.RollReview.append(rolls,a,b)
        return rolls
    }
    val reviewSafeguards get():List<e7.gearscan.opt.RollReview.Safeguard> {
        val item=record?.item?:return emptyList();val q=scoreQuery?:return emptyList();val e=queue.firstOrNull { it.id==selected }?:return emptyList()
        return q.keepStats.map { (stat,value)->e7.gearscan.opt.RollReview.safeguard(item,stat,value,q.reforge,e.reviewAllowed,e.reviewCoverage) }
    }
    val reviewProtected get()=reviewSafeguards.any { it.active(scoreQuery?.keepChance?:0.0) }
    val reviewStatText get():String {
        val item=record?.item?:return "";val q=scoreQuery?:return "";val e=queue.firstOrNull { it.id==selected }?:return ""
        return reviewSafeguards.joinToString("\n") { g->"${g.stat.short} ${g.stat.format(g.minimum)}: "+when { g.now->"already reached";!g.known->"future unknown";g.chance>0->e7.gearscan.opt.GearPotential.percent(g.chance);g.possible->"rare possibility, odds unknown";else->"not reachable" } }+"\n\n"+item.subs.sortedBy { if(it.type==GearStat.SPD)0 else 1 }.joinToString("\n") { sub->
            val line=e7.gearscan.opt.RollReview.line(item,sub.type,q.reforge,e.reviewAllowed,e.reviewCoverage)
            "${sub.type.short} ${sub.type.format(sub.value)} → "+(line.maximum?.let { "up to ${sub.type.format(it)} at +15${if(q.reforge)" & eligible reforge" else ""}" }?:"finish unknown")
        }
    }
    val hasTarget get()=goal!=null||scoreGoal!=null||isRollReview
    val goalOutcome get() = record?.item?.let { item -> scoreGoal?.let { e7.gearscan.opt.GearScore.forecast(item,it).outcome(it.floor) } ?:goal?.let { e7.gearscan.opt.GearPotential.forecast(item, it) } }
    fun useScoreGoal(value:e7.gearscan.opt.GearScore.Goal?) {
        invalidate();scoreGoal=value;goal=null
        queue=queue.map { if(it.id==selected)it.copy(scoreGoal=value,goal=null) else it };persist()
    }
    fun refreshScoreGoals(goals:Map<Long,e7.gearscan.opt.GearScore.Goal>) {
        val next=queue.map { e->goals[e.id]?.let { e.copy(goal=null,scoreGoal=it) }?:e }
        val selectedGoal=goals[selected]?:scoreGoal
        if(next!=queue||selectedGoal!=scoreGoal) { invalidate();queue=next;scoreGoal=selectedGoal;goal=null;persist() }
    }
    fun useGoal(value: e7.gearscan.opt.GearPotential.Goal?) { invalidate(); goal = value;scoreGoal=null;scoreQuery=null;persist() }
    @Volatile var generation = 0
    var editing by mutableStateOf(false)
    val complete get() = !busy && results.size == expected && expected > 0
    val hitCount get() = results.flatMap { it.hits }.distinct().size
    val hasPotential get() = results.any { it.current || it.hits.isNotEmpty() || it.combo }
    val hasFuture get() = results.any { it.hits.isNotEmpty() || it.combo }
    val uncertain get() = !complete || results.any { it.unknown > 0 || it.provisional }
    val title get() = when {
        sellQueueComplete->"List complete"
        isRollReview&&reviewProtected->"Stat safeguard reached"
        isRollReview->reviewRolls?.let { rolls->"${e7.gearscan.opt.RollReview.overallWeakCount(rolls)} of ${rolls.size} rolls below midrange" }?:"Upgrade history unavailable"
        scoreGoal!=null -> goalOutcome?.let { out->when {
            !out.supported->"Catch-up chance unavailable"
            out.matchesNow->"Comparison reached"
            record?.item?.enhance==15&&out.chance==1.0->"Reforge to catch up"
            out.rareOnly->"Needs rare rolls to catch up"
            out.possible->"${out.label()} chance to catch up"
            else->"Cannot reach comparison"
        } }?:"Choose a piece"
        goal != null -> goalOutcome?.let { out -> when {
            out.matchesNow -> "Targets reached"
            record?.item?.enhance == 15 && out.chance == 1.0 -> "Reforge to meet targets"
            out.rareOnly -> "Rare rolls needed"
            out.possible -> "${out.label()} chance to meet targets"
            else -> "Targets no longer reachable"
        } } ?: "Choose a piece"
        busy -> "Checking builds · ${results.size}/$expected"
        error != null -> "Check needs attention"
        expected < 0 -> "Ready to check saved setups"
        expected == 0 -> "Add hero setups to get advice"
        record?.item?.enhance == 15 -> if (hasPotential) "Finished · potential upgrade" else "Finished · no upgrade found"
        hasFuture && results.any { it.provisional && it.hits.isNotEmpty() } -> "Potential upgrade · check incomplete"
        hasFuture -> "Continue · potential upgrade"
        uncertain -> "Undecided · check incomplete"
        results.any { it.current } -> "Pause · useful as it is"
        else -> "Pause · no upgrade found"
    }

    init { runCatching { restore(JSONObject(file.readText()), write = false) }.onFailure { if (file.exists()) error = "Saved upgrade history could not be read. Your scan is unchanged." } }

    fun select(item: Item, scan: Long, keepQueue: Boolean = false) {
        if (!keepQueue || queue.none { it.id == item.id }) { queue = emptyList();scoreQuery=null;queueScan=0 }
        generation++; busy = false; editing = false; error = null
        if (selected != item.id || records[item.id]?.scan != scan) { goal = null;scoreGoal=null }
        if (selected != item.id) { results = emptyList(); signature = ""; expected = -1 }
        selected = item.id
        queue.firstOrNull { it.id == item.id }?.let { goal = it.goal;scoreGoal=it.scoreGoal }
        val old = records[item.id]
        if (old == null || old.scan != scan) {
            records = records + (item.id to UpgradeRecord(scan, listOf(UpgradeRecord.fromScan(item))))
            results = emptyList(); signature = ""; expected = -1
            notice = if (old != null) "Started from your newer scan; the scan takes priority over manual entries." else null
        }
        persist()
    }

    fun commit(main: Double, values: List<Double>, added: Pair<GearStat, Double>?, correction: Boolean): Boolean {
        val r = record ?: return false
        val next = if (correction) r.item.enhance else ((r.item.enhance / 3 + 1) * 3).coerceAtMost(15)
        return runCatching {
            val update = r.record(next, main, values, added, correction)
            records = records + (update.item.id to update)
            invalidate(); editing = false; persist(); requestCheck?.invoke(); true
        }.getOrElse { error = it.message ?: "Could not record this step."; false }
    }

    fun undo() {
        val r = record ?: return
        if (r.history.size <= 1) return
        records = records + (r.item.id to r.undo())
        invalidate(); editing = false; persist(); requestCheck?.invoke()
    }

    fun invalidate() { generation++; busy = false; results = emptyList(); signature = ""; error = null }
    fun begin(sig: String, total: Int) {
        if (sig != signature) { results = emptyList(); signature = sig; attempt = 0 }
        else if (results.any { it.unknown > 0 || it.provisional }) attempt = (attempt + 1).coerceAtMost(3)
        expected = total; busy = total > 0; error = null; persist()
    }
    fun checkpoint(value: List<UpgradeCheck.HeroResult>) { results = value; persist() }
    fun toJson() = JSONObject().put("queueBasis",queueBasis).put("queueSource",queueSource).put("queueScan",queueScan).put("scoreGoal",scoreGoal?.toJson()?:JSONObject.NULL).put("scoreQuery",scoreQuery?.toJson()?:JSONObject.NULL).put("queue", JSONArray(queue.map { it.toJson() })).put("goal", goal?.toJson() ?: JSONObject.NULL).put("records", JSONArray(records.values.map { it.toJson() })).put("selected", selected ?: JSONObject.NULL)
        .put("signature", signature).put("attempt", attempt).put("expected", expected).put("results", JSONArray(results.map { it.toJson() }))
    fun restore(j: JSONObject, write: Boolean = true) {
        generation++; busy = false; editing = false;cleared=emptySet();canUndoSold=false
        val a = j.optJSONArray("records") ?: JSONArray()
        records = (0 until a.length()).map { UpgradeRecord.fromJson(a.getJSONObject(it)) }.associateBy { it.item.id }
        selected = if (j.isNull("selected")) null else j.optLong("selected").takeIf { it in records }
        scoreGoal=j.optJSONObject("scoreGoal")?.let { runCatching { e7.gearscan.opt.GearScore.Goal.of(it) }.getOrNull() }
        scoreQuery=j.optJSONObject("scoreQuery")?.let { runCatching { SellQuery.of(it).copy(scoreFilter=it.optBoolean("scoreFilter",true)) }.getOrNull() }
        goal = j.optJSONObject("goal")?.let { runCatching { e7.gearscan.opt.GearPotential.Goal.of(it) }.getOrNull() }
        val q = j.optJSONArray("queue") ?: JSONArray()
        queue = (0 until q.length()).mapNotNull { i -> runCatching {
            val e = q.getJSONObject(i)
            QueueEntry(e.getLong("id"), e.optJSONObject("goal")?.let(e7.gearscan.opt.GearPotential.Goal::of), e.optString("setName"),
                if (e.isNull("setIcon")) null else e.optString("setIcon"),e.optJSONObject("scoreGoal")?.let(e7.gearscan.opt.GearScore.Goal::of),e7.gearscan.opt.RollReview.rollsOf(e.optJSONArray("reviewRolls")),e.optInt("reviewHistory",1),e.optBoolean("reviewAllowed",false),e.optBoolean("reviewCoverage",false))
        }.getOrNull()?.takeIf { it.id in records } }.distinctBy { it.id }
        queueBasis=j.optString("queueBasis",j.optJSONObject("scoreQuery")?.optString("rollBasis","UPGRADES")?:e7.gearscan.opt.RollReview.BASIS)
        queueSource=j.optString("queueSource")
        queueScan=j.optLong("queueScan",queue.mapNotNull { records[it.id]?.scan }.minOrNull()?:0L)
        signature = j.optString("signature"); attempt = j.optInt("attempt"); expected = j.optInt("expected", -1)
        val r = j.optJSONArray("results") ?: JSONArray()
        results = (0 until r.length()).map { UpgradeCheck.HeroResult.of(r.getJSONObject(it)) }
        if (write) persist()
    }
    fun clear() { restore(JSONObject()) }
    private fun persist() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson().toString())
        check(tmp.renameTo(file)) { "Could not save upgrade history" }
    }
}
