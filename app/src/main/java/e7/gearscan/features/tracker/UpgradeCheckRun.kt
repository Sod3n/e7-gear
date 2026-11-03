package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.improveheroes.ImproveHeroesFeature
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.data.Box
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.HeroUpgrades
import e7.gearscan.opt.Setup
import e7.gearscan.opt.UpgradeCheck
import e7.gearscan.state.Work
import e7.gearscan.ui.Outcome
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class UpgradeCheckRun(private val t: TrackerFeature, private val ctx: FeatureContext, private val check: Work<Unit>, private val scan: ScanFeature, private val records: RecordsFeature, private val heroesFeature: HeroesFeature, private val pool: PoolFeature, private val marks: MarksFeature, private val improveHeroes: ImproveHeroesFeature, private val content: ContentFeature) {
    var results: List<UpgradeCheck.HeroResult> by mutableStateOf(emptyList())
        internal set
    var signature = ""
        internal set
    var attempt = 0
        internal set
    var expected by mutableStateOf(-1)
        internal set
    var busy by mutableStateOf(false)
        internal set

    private var pieceWithout: Pair<String, ConcurrentHashMap<Long, HeroUpgrades.Base>>? = null

    val complete get() = !busy && results.size == expected && expected > 0
    val hitCount get() = results.flatMap { it.hits }.distinct().size

    // finishes each hero was checked with (the same ones for every hero)
    val total get() = results.maxOfOrNull { it.total } ?: 0
    val chance get() = if (total > 0) hitCount * 100 / total else null

    // heroes the piece as it is now is an upgrade for with nothing else moving, the biggest gain first
    val equipHeroes get() = results.filter { it.swapNow }.sortedByDescending { it.gain ?: 0.0 }
    val betterBet: Pair<Long, String>? get() =
        results.filter { it.hits.isNotEmpty() || it.swapNow }.maxByOrNull { it.hits.size }
            ?.takeIf { it.better.isNotEmpty() }?.let { it.better.first() to it.betterText }
    val hasPotential get() = results.any { it.current || it.hits.isNotEmpty() || it.combo }
    val hasFuture get() = results.any { it.hits.isNotEmpty() || it.combo }
    val uncertain get() = !complete || results.any { it.unknown > 0 || it.provisional }

    fun stopCheck() {
        check.cancel()
        busy = false
    }

    fun clear() {
        stopCheck()
        results = emptyList()
        signature = ""
    }

    private fun resumable(sig: String): List<UpgradeCheck.HeroResult> =
        if (sig == signature) results.filter { it.unknown == 0 && !it.provisional } else emptyList()

    internal fun begin(
        sig: String,
        total: Int,
    ) {
        if (sig != signature) {
            results = emptyList()
            signature = sig
            attempt = 0
        } else if (results.any { it.unknown > 0 || it.provisional }) {
            attempt = (attempt + 1).coerceAtMost(3)
        }
        expected = total
        busy = total > 0
        t.error = null
        t.persist()
    }

    internal fun checkpoint(value: List<UpgradeCheck.HeroResult>) {
        results = value
        t.persist()
    }

    fun runCheck() {
        if (t.hasTarget) {
            t.refreshScoreGoal()
            busy = false
            return
        }
        val r = t.record ?: return
        val b0 = scan.box
        if (r.scan != scan.lastScan || b0?.itemsById?.containsKey(r.item.id) != true) {
            t.error = "Your scan changed. Choose the piece again in Track upgrades."
            return
        }
        val observed = records.records.values.filter { it.scan == scan.lastScan }.associate { it.item.id to it.item }
        val b = Box(JSONObject(b0.toText()).put("items", JSONArray(b0.items.map { item -> UpgradeRecord.itemJson(observed[item.id] ?: item) })))
        val setups =
            heroesFeature.gearSetups(b, pool.inUse(b) + marks.cleared).map { s ->
                val cfg = HeroConfig.fromJson(s.cfg.toJson())
                Setup(s.hero, s.game, cfg, s.req.copy(), b, content.content, s.canTakeFrom, s.excluded - r.item.id)
            }
        val withRebuilds = improveHeroes.rebuilds
        val raw =
            "upgrade-v3-piece|$withRebuilds|${improveHeroes.minGain}|" + b.toText() + content.content.version + r.toJson().toString() +
                setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() }
        val sig = sha256(raw)
        // each hero's best build without the piece holds for every step recorded on it
        val withoutKey =
            sha256(
                "without-v1|${r.item.id}|" + JSONArray(b.items.filter { it.id != r.item.id }.map { UpgradeRecord.itemJson(it) }) +
                    content.content.version + setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() },
            )
        if (pieceWithout?.first != withoutKey) pieceWithout = withoutKey to ConcurrentHashMap()
        val without = pieceWithout!!.second
        val resume = resumable(sig)
        if (setups.isEmpty() || resume.size == setups.size) {
            begin(sig, setups.size)
            busy = false
            return
        }
        val pieces = PieceCheck(content, marks, heroesFeature, improveHeroes.minGain)
        val out =
            check.start {
                val scope = this
                val alive = { scope.isActive }
                begin(sig, setups.size)
                onCancel { busy = false }
                try {
                    val t0 = System.nanoTime()
                    val done = java.util.Collections.synchronizedList(ArrayList<UpgradeCheck.HeroResult>(resume))
                    withContext(Dispatchers.Default) {
                        HeroUpgrades(PIECE_SIMS).piece(
                            setups, r.item, without, attempt, resume.map { it.id }.toSet(),
                            cancelled = { !alive() },
                        ) { pr ->
                            done += pieces.advice(pr, withRebuilds)
                            val now = synchronized(done) { done.toList() }
                            ctx.scope.launch {
                                if (alive()) {
                                    checkpoint(now)
                                    scope.progress(now.size, setups.size, "${now.size}/${setups.size} heroes")
                                }
                            }
                        }
                    }
                    checkpoint(synchronized(done) { done.toList() })
                    busy = false
                    android.util.Log.i(
                        "E7Upgrade",
                        "checked ${results.size}/${setups.size} heroes, $hitCount/$total finishes for piece " +
                            "${r.item.id} +${r.item.enhance}, %.1f s".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9),
                    )
                    // still the best bet? the same heroes' other unfinished pieces for this slot, through the same check
                    val tops = results.filter { it.hits.isNotEmpty() || it.swapNow }.sortedByDescending { it.hits.size }.take(3)
                    if (r.item.enhance < 15 && tops.isNotEmpty()) {
                        val alts = withContext(Dispatchers.Default) { pieces.compareSlot(setups.filter { s -> tops.any { it.id == s.hero.id } }, r.item, b, withRebuilds) }
                        checkpoint(
                            results.map { h ->
                                alts[h.id]?.let { (lines, better) ->
                                    h.copy(
                                        alts = lines.map { it.second }, better = better,
                                        betterText = lines.firstOrNull { it.first == better.firstOrNull() }?.second.orEmpty(),
                                    )
                                } ?: h
                            },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    busy = false
                    t.error = "Check stopped. Recorded steps are saved; recheck it in E7 Gear to continue."
                }
            }
        if (out is Outcome.Failed) t.error = out.text
    }

    private fun sha256(raw: String) =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.US, it) }
}
