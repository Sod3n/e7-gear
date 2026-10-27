package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.data.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Full-build tests, never per-slot scores. Every completed hero is a restartable checkpoint. */
class UpgradeCheck(private val samples: Int = 30, private val heroMs: Long = 3500) {
    /** [total]: finishes tried; [minimal]: the weakest finishes that still help, as text; [typical]/[nowGain]: gains as text. */
    data class HeroResult(val id: Long, val name: String, val hits: List<Int>, val current: Boolean, val combo: Boolean,
                          val unknown: Int, val provisional: Boolean, val gain: Double?, val changes: String,
                          val total: Int = 30, val minimal: List<String> = emptyList(), val typical: String = "", val nowGain: String = "",
                          /** Equipping the piece as it is, nothing else moving, helps; [build]: the final build to show, as lines. */
                          val swapNow: Boolean = false, val build: List<String> = emptyList()) {
        fun toJson() = JSONObject().put("id", id).put("name", name).put("hits", JSONArray(hits)).put("current", current)
            .put("combo", combo).put("unknown", unknown).put("provisional", provisional).put("gain", gain ?: JSONObject.NULL).put("changes", changes)
            .put("total", total).put("minimal", JSONArray(minimal)).put("typical", typical).put("nowGain", nowGain)
            .put("swapNow", swapNow).put("build", JSONArray(build))
        companion object {
            fun of(j: JSONObject) = HeroResult(j.getLong("id"), j.getString("name"), j.getJSONArray("hits").let { a -> (0 until a.length()).map(a::getInt) },
                j.getBoolean("current"), j.getBoolean("combo"), j.getInt("unknown"), j.getBoolean("provisional"),
                if (j.isNull("gain")) null else j.getDouble("gain"), j.getString("changes"), j.optInt("total", 30),
                j.optJSONArray("minimal")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(), j.optString("typical"), j.optString("nowGain"),
                j.optBoolean("swapNow"), j.optJSONArray("build")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty())
        }
    }

    fun check(item: Item, setups: List<Setup>, resume: List<HeroResult> = emptyList(), cancelled: () -> Boolean = { false },
              saved: (List<HeroResult>) -> Unit = {}): List<HeroResult> {
        require(UpgradeRecord.supported(item))
        val out = resume.filter { r -> setups.any { it.hero.id == r.id } }.toMutableList()
        val finishes = if (item.enhance == 15) listOf(item) else Finish.of(item, samples)
        val start = System.nanoTime()
        for (setup in setups) {
            if (cancelled() || System.nanoTime() - start > 240_000_000_000L) break
            if (out.any { it.id == setup.hero.id }) continue
            out += hero(item, finishes, setup, cancelled)
            saved(out.toList())
        }
        return out
    }

    private fun hero(item: Item, finishes: List<Item>, s: Setup, cancelled: () -> Boolean): HeroResult {
        val end = System.nanoTime() + heroMs * 1_000_000
        fun remaining() = ((end - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        fun copy(excluded: Set<Long>) = Setup(s.hero, s.game, s.cfg, s.req, s.box, s.content, s.canTakeFrom, excluded)
        val baseline = copy(s.excluded + item.id)
        val base = SearchRun(baseline, 1).also { it.run(1, minOf(900, remaining())) }
        val without = base.builds().firstOrNull()
        var best = without
        val v0 = best?.value ?: Double.NEGATIVE_INFINITY
        val obj = Objectives.of(s.req, s.hb)
        val unit = when (s.req.goal) {
            Goal.TARGETS -> (0 until 8).filter { !s.req.targets[it].isNaN() }.map { maxOf(1, s.req.weights[it]) }.average().takeIf { it.isFinite() } ?: 1.0
            Goal.SPEED -> Objectives.rollSize(s.hb)[3]
            else -> maxOf(1e-6, kotlin.math.abs(v0) * 0.01).takeIf { it.isFinite() } ?: 1.0
        }
        var reference = v0
        var bar = if (v0.isFinite()) v0 + 0.05 * unit else v0
        val forced = copy(s.excluded + s.box.items.filter { it.slot == item.slot }.map { it.id })
        val maxs = s.maxs()
        val cares = (obj.uses.toList() + (0 until 8).filter { !s.req.mins[it].isNaN() }).distinct()
        val sameSet = s.box.items.filter { it.id != item.id && it.slot == item.slot && it.set == item.set && baseline.eligible(it) }
            .map { Calc.itemVec(it, s.hb, s.req.reforged) }
        fun dominated(q: Item): Boolean {
            val v = Calc.itemVec(q, s.hb, s.req.reforged)
            // A same-set replacement preserves every set bonus. Bounded stats must not grow past a maximum.
            return sameSet.any { p -> cares.all { p[it] >= v[it] - 1e-8 } &&
                (0 until 8).all { maxs[it].isNaN() || p[it] <= v[it] + 1e-8 } }
        }
        var example: Build? = null
        var exampleGain: Double? = null
        var unknown = 0
        fun legal(q: Item) = (s.req.allowLocked || !item.locked || item.equippedOn == s.hero.id) &&
            s.req.mains[q.slot].let { it.isNullOrEmpty() || q.mainType in it }
        fun value(items: List<Item>): Double {
            if (s.req.setAlts.isNotEmpty()) {
                val counts = items.groupingBy { it.set }.eachCount()
                if (s.req.setAlts.none { a -> a.all { (k, n) -> (counts[k] ?: 0) >= n } }) return Double.NEGATIVE_INFINITY
            }
            val f = Calc.final(s.hb, items, s.req.reforged, s.content)
            val maxs = s.maxs()
            if ((0 until 8).any { (!s.req.mins[it].isNaN() && f.stats[it] < s.req.mins[it]) || (!maxs[it].isNaN() && f.stats[it] > maxs[it]) }) return Double.NEGATIVE_INFINITY
            return obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
        }
        fun test(q: Item): Boolean {
            if (!legal(q) || dominated(q)) return false
            val swap = best?.items?.map { if (it.slot == q.slot) q else it }
            val direct = swap?.let(::value) ?: Double.NEGATIVE_INFINITY
            val b = if (direct > bar) s.build(swap!!, direct) else {
                if (cancelled() || remaining() < 20) { unknown++; return false }
                val run = SearchRun(forced, 1, extra = listOf(q), startThreshold = bar)
                val ok = run.anyBuild(1, minOf(90, remaining()))
                if (ok == null) unknown++
                if (ok == true) run.builds().firstOrNull() else null
            }
            if (b != null && (example == null || b.value > example!!.value)) {
                example = b
                exampleGain = if (reference.isFinite()) (b.value - reference) / unit else null
            }
            return b != null
        }
        var provisional = base.engine.timedOut
        var currentBuild: Build? = null
        val current = if (item.enhance == 15) test(item) else if (!legal(item) || dominated(item)) false else {
            if (remaining() < 20 || cancelled()) { unknown++; false } else {
                val now = SearchRun(forced, 1, extra = listOf(item))
                now.run(1, minOf(350, remaining()))
                provisional = provisional || now.engine.timedOut
                currentBuild = now.builds().firstOrNull()
                val useful = currentBuild?.value?.let { it > bar } == true
                // Continue must beat what this piece already does at its CURRENT level, not just a box without it.
                if (currentBuild != null && currentBuild!!.value > reference) {
                    best = currentBuild; reference = currentBuild!!.value; bar = reference + 0.05 * unit
                }
                useful
            }
        }
        val hits = finishes.indices.filter { test(finishes[it]) }
        var combo = false
        val partners = s.box.items.filter { p -> p.id != item.id && p.id !in s.excluded && p.slot != item.slot && p.enhance < 15 && UpgradeRecord.supported(p) &&
            (s.req.allowLocked || !p.locked || p.equippedOn == s.hero.id) && s.req.mains[p.slot].let { it.isNullOrEmpty() || p.mainType in it } }
        val candidates = finishes.filter { legal(it) && !dominated(it) }
        if (item.enhance < 15 && partners.isNotEmpty() && candidates.isNotEmpty()) for (round in 0 until 8) {
            if (cancelled() || remaining() < 40) { unknown++; break }
            val extra = partners.mapIndexed { i, p ->
                val q = Finish.of(p, 1, Random(p.id.hashCode() * 31 + round)).first()
                Item(-100_000L - i, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null)
            }
            // Same partners plus the tracked piece AS IT IS NOW: only the extra upgrading can take credit.
            val partnerBase = SearchRun(baseline, 1, extra = extra + if (legal(item)) listOf(item) else emptyList()).also { it.run(1, minOf(80, remaining())) }
            val partnerValue = partnerBase.builds().firstOrNull()?.value ?: Double.NEGATIVE_INFINITY
            val threshold = maxOf(bar, if (partnerValue.isFinite()) partnerValue + 0.05 * unit else partnerValue)
            val q = candidates[round % candidates.size]
            if (!legal(q)) continue
            val r = SearchRun(forced, 1, extra = extra + q, startThreshold = threshold)
            val ok = r.anyBuild(1, minOf(80, remaining()))
            if (partnerBase.engine.timedOut || ok == null) unknown++
            // A partial partner baseline cannot establish that THIS piece contributed.
            if (ok == true && !partnerBase.engine.timedOut) { combo = true; break }
        }
        val comparison = if (example == null && current) without else best
        if (example == null && current) {
            example = currentBuild
            exampleGain = if (v0.isFinite() && currentBuild != null) (currentBuild!!.value - v0) / unit else null
        }
        val changes = if (comparison != null && example != null) Stat.entries.mapNotNull { stat ->
            val delta = example!!.final.stats[stat.ordinal] - comparison.final.stats[stat.ordinal]
            if (kotlin.math.abs(delta) < 0.5) null else "${stat.short} ${if (delta > 0) "+" else ""}${fmtNum(kotlin.math.round(delta))}"
        }.joinToString(" · ") else if (example != null) "Makes a build that meets the setup" else ""
        return HeroResult(s.hero.id, s.hero.name, hits, current, combo, unknown, provisional, exampleGain, changes)
    }
}
