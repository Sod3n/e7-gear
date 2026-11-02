package e7.gearscan.features.improveheroes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.Goal
import e7.gearscan.data.Item
import e7.gearscan.data.Match
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Stat
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.data.fmtNum
import e7.gearscan.opt.Finish
import e7.gearscan.opt.HeroUpgrades
import e7.gearscan.opt.Objectives
import e7.gearscan.opt.Setup
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.state.blocking
import e7.gearscan.ui.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Saved Improve heroes results of another format are dropped on load. */
const val HERO_UPGRADES_FORMAT = 2

/** The hero-upgrade check for the inputs behind [key] (see [ImproveHeroesFeature.key]); [result] null until one finished. */
data class HeroUpgradeState(
    val scan: Long,
    val key: String,
    val result: HeroUpgrades.Result?,
    val running: Boolean,
    val done: Int = 0,
    val total: Int = 0,
    val finishedAt: Long = 0,
    /** Each "check the rest" doubles the time per search. */
    val attempt: Int = 0,
)

/** A hero's final build with a piece upgraded: six slots, where each piece comes from, and the stats against what it wears. */
class FinalBuild(
    val hero: OwnedHero,
    val pieceId: Long,
    val items: List<Item>,
    val from: List<String>,
    val sets: Pair<String, String>,
    val stats: List<Triple<Stat, Double, Double>>,
    val gain: String,
    val note: String,
)

/** A piece for a hero as listed: [hits] of [sims] finishes gain at least the minimum; [expected] = their gains summed over all finishes, in rolls. [usual]: the finish (index among its piece's) behind [typical]. */
class HeroUpgradeRow(
    val pick: HeroUpgrades.Pick,
    val hits: Int,
    val sims: Int,
    val typical: String,
    val best: String,
    val expected: Double,
    val usual: Int = -1,
    val changes: String = "",
) {
    val chance get() = hits * 100 / sims
}

/** [bestNow]: match % of the best build the hero can make now (targets goal), the bar every finish is measured against. */
class HeroUpgradeHero(val hero: OwnedHero, val base: HeroUpgrades.Base, val rows: List<HeroUpgradeRow>, val bestNow: Int?, val open: Int)

class ImproveHeroesFeature(g: AppGraph) : Feature(g, "improveHeroes") {
    /** Smallest gain a finish must bring to be listed, in rolls ([HeroUpgrades.unit]). */
    var minGain by saved("minGain", 0.5, Codecs.double)

    /** Off: a finish counts only put in place of the hero's piece in that slot, nothing else moving. On: any build the search finds. */
    var rebuilds by saved("rebuilds", false, Codecs.bool)
    val result = savedFile("hero_upgrades.json", backup = false, path = "hero_upgrades.json")
    val progress = savedFile("hero_upgrades_progress.json", backup = false, path = "hero_upgrades_progress.json")
    val run = work<Unit>("Improve heroes check", Weight.Heavy, notice = "Finding upgrades for your heroes")

    var heroUpgrades: HeroUpgradeState? by mutableStateOf(null)
        internal set

    /** A stopped or killed check for the current gear and setups waits in its progress file. */
    var resumable by mutableStateOf(false)
        private set
    var finalBuild: FinalBuild? by mutableStateOf(null)
        private set
    var finalBuildBusy by mutableStateOf(false)
        private set

    /** Stat changes per (hero, piece, rebuilds), filled ahead in the background so rows draw with them. */
    val changes = mutableStateMapOf<Triple<Long, Long, Boolean>, List<Pair<Stat, Double>>>()

    private var keyMemo: Pair<List<Any?>, String>? = null
    private var usualInputs: Pair<List<Any?>, Pair<List<Setup>, List<Item>>>? = null
    private var changesFor: Any? = null

    private val box get() = g.scan.box
    private val content get() = g.content.content

    fun useMinGain(rolls: Double) {
        minGain = rolls
        g.scan.touch()
    }

    fun useRebuilds(on: Boolean) {
        rebuilds = on
        g.scan.touch()
    }

    /** The heroes the check covers: every hero with a setup, as in [e7.gearscan.features.heroes.HeroesFeature.gearSetups]. */
    fun heroes(): List<OwnedHero> =
        box?.heroes.orEmpty().filter { h ->
            g.heroes.gameHero(h) != null &&
                g.heroes.configs[h.id]?.request?.let { r -> r.goal != Goal.TARGETS || r.targets.any { !it.isNaN() } } == true
        }

    private fun sha256(raw: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.US, it) }

    /** What the answer depends on: scan, recorded upgrades, cleared pieces, the pool's protected gear, setups, content. */
    fun key(): String {
        val memoKey =
            listOf(box, g.scan.lastScan, g.tracker.records, g.marks.cleared, g.pool.tiers, g.pool.extra, g.heroes.tiers, g.scan.rev, content.version)
        keyMemo?.takeIf { it.first == memoKey }?.let { return it.second }
        val raw =
            "hero-upgrades-v2|${g.scan.lastScan}|" +
                g.tracker.records.values.filter { it.scan == g.scan.lastScan }.sortedBy { it.item.id }.joinToString { it.toJson().toString() } +
                "|${g.marks.cleared.sorted()}|${g.pool.members().map { it.id }.sorted()}|${content.version}|" +
                heroes().joinToString(",") { "${it.id}=${g.heroes.setupKey(it.id)}" }
        return sha256(raw).also { keyMemo = memoKey to it }
    }

    /** The shown result was made for other gear or setups: still readable, but "Check again" gives the current answer. */
    val stale: Boolean get() = heroUpgrades?.let { !it.running && it.result != null && it.key != key() } == true

    /** The box as recorded (manual upgrades of this scan applied, cleared pieces out), the heroes, the pieces to try, and a full fingerprint. */
    private fun inputs(): Triple<List<Setup>, List<Item>, String>? {
        val b0 = box ?: return null
        val observed = g.tracker.records.values.filter { it.scan == g.scan.lastScan }.associate { it.item.id to it.item }
        val b =
            Box(
                JSONObject(b0.toText()).put(
                    "items",
                    JSONArray(b0.items.filter { it.id !in g.marks.cleared }.map { item -> UpgradeRecord.itemJson(observed[item.id] ?: item) }),
                ),
            )
        val setups = g.heroes.gearSetups(b, g.heroes.inUse(b))
        val pieces = b.items.filter { it.enhance < 15 && UpgradeRecord.supported(it) }.sortedBy { it.id }
        val full =
            sha256(
                "hero-upgrades-v2|${HeroUpgrades.SIMS}|" + b.toText() + content.version +
                    setups.joinToString { s -> s.hero.id.toString() + s.cfg.toJson() + s.req.toJson() + s.excluded.sorted() },
            )
        return Triple(setups, pieces, full)
    }

    private fun writeProgress(full: String, light: String, attempt: Int, p: HeroUpgrades.Progress, finished: Boolean) =
        synchronized(progress) {
            val text = JSONObject().put("full", full).put("light", light).put("attempt", attempt).put("finished", finished).put("progress", p.toJson()).toString()
            progress.write(text)
        }

    /** [more]: another pass over the searches that ran out of time, with twice the time each; everything settled is kept. */
    fun start(more: Boolean = false) {
        if (heroUpgrades?.running == true) return
        val (setups, pieces, full) = inputs() ?: return
        val light = key()
        val saved = runCatching { JSONObject(progress.read().orEmpty()) }.getOrNull()?.takeIf { it.optString("full") == full }
        val resume = saved?.optJSONObject("progress")?.let { runCatching { HeroUpgrades.Progress.of(it) }.getOrNull() } ?: HeroUpgrades.Progress()
        val attempt = (saved?.optInt("attempt") ?: 0) + if (more && saved != null) 1 else 0
        val scan = g.scan.lastScan
        g.messages.show(
            run.start {
                val scope = this
                val alive = { scope.isActive }
                heroUpgrades = HeroUpgradeState(scan, light, heroUpgrades?.result?.takeIf { heroUpgrades?.key == light }, running = true, attempt = attempt)
                resumable = false
                val check = HeroUpgrades()
                onCancel {
                    check.cancel()
                    stopped()
                }
                var written = 0L
                // the writes after the run happen even when it was stopped: they are what makes it resumable
                val state =
                    blocking("hero-upgrades") {
                        val t0 = System.nanoTime()
                        val r =
                            runCatching {
                                check.run(
                                    setups, pieces, attempt, cancelled = { !alive() },
                                    progress = { done, total ->
                                        g.scope.launch { if (alive()) heroUpgrades = heroUpgrades?.copy(done = done, total = total) }
                                        scope.progress(done, total, "${done * 100 / total.coerceAtLeast(1)}%")
                                    },
                                    resume = resume,
                                ) {
                                    // a few seconds of work at most is lost if the app is killed
                                    if (System.currentTimeMillis() - written > 3000) {
                                        written = System.currentTimeMillis()
                                        writeProgress(full, light, attempt, it, false)
                                    }
                                }
                            }.onFailure { android.util.Log.e("E7Heroes", "hero upgrade check failed", it) }.getOrNull()
                        val finished = r != null && alive()
                        // a newer check owns the file
                        if (alive() || !run.busy) writeProgress(full, light, attempt, resume, finished)
                        val done = r?.takeIf { finished }?.let { HeroUpgradeState(scan, light, it, running = false, finishedAt = System.currentTimeMillis(), attempt = attempt) }
                        if (done != null) {
                            runCatching {
                                result.write(
                                    JSONObject().put("format", HERO_UPGRADES_FORMAT).put("scan", scan).put("key", light)
                                        .put("finishedAt", done.finishedAt).put("attempt", attempt).put("result", r.toJson()).toString(),
                                )
                            }
                            runCatching {
                                android.util.Log.i(
                                    "E7Heroes",
                                    "hero upgrades: ${setups.size} heroes, ${pieces.size} pieces, ${r.picks.size} picks, " +
                                        "${r.unknownSteps} open steps, attempt $attempt, %.1f s".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9),
                                )
                            }
                        }
                        done
                    }
                if (state == null) {
                    heroUpgrades = heroUpgrades?.copy(running = false)
                    resumable = true
                    return@start
                }
                heroUpgrades = state
                g.platform.notifyHeroUpgradesDone(heroUpgrades?.result?.let { r -> view(r).count { it.rows.isNotEmpty() } } ?: 0)
            },
        )
    }

    private fun stopped() {
        heroUpgrades = heroUpgrades?.copy(running = false)?.takeIf { it.result != null }
        resumable = true
    }

    fun stop() = run.cancel()

    fun track(piece: Long): Outcome = g.tracker.trackPiece(piece)

    fun trackAll(pieces: List<Long>): Outcome = g.tracker.trackPieces(pieces)

    /** The stats the hero's setup scores, now (worn) and with [items]. */
    private fun scoredStats(s: Setup, items: List<Item>): List<Triple<Stat, Double, Double>> {
        val now = s.build(s.current().values.toList()).final.stats
        val after = s.build(items).final.stats
        val obj = Objectives.of(s.req, s.hb)
        val cares = (obj.uses.filter { it < 8 } + (0 until 8).filter { !s.req.mins[it].isNaN() }).distinct()
        return Stat.entries.filter { it.ordinal in cares }.map { Triple(it, now[it.ordinal], after[it.ordinal]) }
    }

    /** Opens the final build of [row]'s usual finish for [heroId], worked out off the main thread. */
    fun openFinalBuild(heroId: Long, row: HeroUpgradeRow) {
        val r = heroUpgrades?.result ?: return
        val h = g.heroes.hero(heroId) ?: return
        finalBuild = null
        finalBuildBusy = true
        val withRebuilds = rebuilds
        g.scope.launch {
            val fb =
                withContext(Dispatchers.Default) {
                    val (setups, pieces, _) = inputs() ?: return@withContext null
                    val s = setups.firstOrNull { it.hero.id == heroId } ?: return@withContext null
                    val piece = pieces.firstOrNull { it.id == row.pick.piece } ?: return@withContext null
                    val j = row.usual.takeIf { it >= 0 } ?: 0
                    val f = Finish.of(piece, r.sims)[j]
                    val finish = Item(piece.id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, f.subs, piece.locked, piece.equippedOn, main15 = f.main15)
                    val worn = s.current().values.toList().takeIf { it.size == 6 }
                    val items =
                        if (withRebuilds) {
                            HeroUpgrades.rebuild(s, finish)?.items
                        } else {
                            worn?.map { if (it.slot == piece.slot) finish else it }
                        }
                    items ?: return@withContext null
                    FinalBuild(
                        h, piece.id, items.sortedBy { it.slot.ordinal }, g.heroes.buildFrom(heroId, items.sortedBy { it.slot.ordinal }, piece.id),
                        HeroUpgrades.setsLabel(s.current().values, content) to HeroUpgrades.setsLabel(items, content),
                        scoredStats(s, items), row.typical,
                        if (withRebuilds) "The best build with a usual finish of this piece" else "What the hero wears, with a usual finish of this piece in its slot",
                    )
                }
            finalBuild = fb
            finalBuildBusy = false
        }
    }

    /** Works out [rows]' stat changes off the main thread, top rows first; rows already known are skipped. */
    suspend fun warmChanges(rows: List<Pair<Long, HeroUpgradeRow>>) {
        val r = heroUpgrades?.result ?: return
        val key = listOf(r, g.scan.lastScan, g.scan.rev, g.marks.cleared)
        if (changesFor != key) {
            changes.clear()
            changesFor = key
        }
        val withRebuilds = rebuilds
        for ((hero, row) in rows) {
            val k = Triple(hero, row.pick.piece, withRebuilds)
            if (k !in changes) changes[k] = statChanges(hero, row)
        }
    }

    /**
     * The hero's stat changes from [row], for cards: with This piece only, the row's usual finish put in the hero's slot
     * against what it wears; with rebuilds, the best finish's changes the check stored. Only stats the setup scores.
     */
    suspend fun statChanges(heroId: Long, row: HeroUpgradeRow): List<Pair<Stat, Double>> {
        if (rebuilds) {
            return row.changes.split(" · ").mapNotNull { part ->
                val name = part.substringBeforeLast(' ')
                val v = part.substringAfterLast(' ').toDoubleOrNull() ?: return@mapNotNull null
                Stat.entries.firstOrNull { it.short == name }?.let { it to v }
            }
        }
        val r = heroUpgrades?.result ?: return emptyList()
        val key = listOf(r, g.scan.lastScan, g.scan.rev, g.marks.cleared)
        return withContext(Dispatchers.Default) {
            val (setups, pieces) =
                synchronized(this@ImproveHeroesFeature) {
                    usualInputs?.takeIf { it.first == key }?.second ?: run {
                        val (s, p, _) = inputs() ?: return@withContext emptyList()
                        (s to p).also { usualInputs = key to it }
                    }
                }
            val s = setups.firstOrNull { it.hero.id == heroId } ?: return@withContext emptyList()
            val piece = pieces.firstOrNull { it.id == row.pick.piece } ?: return@withContext emptyList()
            val f = Finish.of(piece, r.sims)[row.usual.takeIf { it >= 0 } ?: 0]
            val finish = Item(piece.id, f.slot, f.set, f.rank, f.level, 15, f.mainType, f.mainValue, f.subs, piece.locked, piece.equippedOn, main15 = f.main15)
            val worn = s.current().values.toList().takeIf { it.size == 6 } ?: return@withContext emptyList()
            scoredStats(s, worn.map { if (it.slot == piece.slot) finish else it }).mapNotNull { (stat, now, after) ->
                val d = Math.round(after - now).toDouble()
                if (d == 0.0) null else stat to d
            }
        }
    }

    /** Every checked hero (higher tiers first, then by what its best piece is worth), each with the pieces that gain at least [minGain] rolls. */
    fun view(r: HeroUpgrades.Result, minGain: Double = this.minGain, rebuilds: Boolean = this.rebuilds): List<HeroUpgradeHero> {
        val byHero = r.picks.groupBy { it.hero }
        return r.bases.values.mapNotNull { base ->
            val h = g.heroes.hero(base.id) ?: return@mapNotNull null
            val req = g.heroes.configs[h.id]?.request ?: return@mapNotNull null
            val scale =
                if (req.goal == Goal.TARGETS) {
                    g.heroes.mainsOnly(h.id, req)?.let { floor ->
                        g.heroes.rolls(h.id)?.let { roll -> Match.start(floor, req.targets, req.weights, roll).takeIf { it > 0 }?.let { 100 / it } }
                    }
                } else {
                    null
                }
            // the starting value: the best build now, or (nothing else moving) what the hero wears
            val ref = if (rebuilds) base.value else base.worn

            fun label(v: Double): String {
                val v0 = ref ?: return "makes a build"
                val d = v - v0

                fun one(x: Double) = fmtNum(Math.round(x * 10) / 10.0)
                return when (req.goal) {
                    Goal.TARGETS -> scale?.let { "+${one(d * it)} match" } ?: "+${one(d / base.unit)} rolls"
                    Goal.SPEED -> "+${one(d)} speed"
                    Goal.GEAR_SCORE -> "+${one(d)} score"
                    Goal.DAMAGE -> "+${one(100 * d / kotlin.math.abs(v0))}% damage"
                    Goal.EHP -> "+${one(100 * d / kotlin.math.abs(v0))}% EHP"
                    Goal.CP -> "+${one(100 * d / kotlin.math.abs(v0))}% CP"
                }
            }
            val picks = byHero[h.id].orEmpty()
            val rows =
                picks.mapNotNull { p ->
                    // nothing else moving, or the best build however it is made
                    val ranked = if (rebuilds) p.values.indices.map { p.hitIdx.getOrElse(it) { -1 } to p.values[it] } else p.swaps
                    val gains = if (ref == null) ranked.map { 1.0 } else ranked.map { (it.second - ref) / base.unit }.filter { it >= minGain }
                    if (gains.isEmpty()) return@mapNotNull null
                    val kept = ranked.take(gains.size)
                    HeroUpgradeRow(
                        p, gains.size, r.sims, label(kept[kept.size / 2].second), label(kept.first().second), gains.sum() / r.sims,
                        kept[kept.size / 2].first, if (rebuilds) p.changes else p.swapChanges,
                    )
                }.sortedWith(compareByDescending<HeroUpgradeRow> { it.expected }.thenByDescending { it.hits }.thenBy { it.pick.piece })
            val baseBuild = g.heroes.setup(h.id)?.let { s -> base.items.mapNotNull { box?.itemsById?.get(it) }.takeIf { it.size == 6 }?.let { s.build(it) } }
            HeroUpgradeHero(
                h, base, rows, if (rebuilds) baseBuild?.let { g.heroes.matchOf(h.id, it)?.pct } else g.heroes.matchFor(h.id)?.pct,
                picks.count { it.unknown > 0 },
            )
        }.sortedWith(
            compareByDescending<HeroUpgradeHero> { g.heroes.tier(it.hero.id) }.thenByDescending { it.rows.firstOrNull()?.expected ?: -1.0 }
                .thenBy { g.heroes.heroName(it.hero) },
        )
    }

    /** Reads the saved result back for [b] (none for no box) and whether a stopped check is waiting. */
    fun reload(b: Box?) {
        // results saved before the swap fields existed can't answer "this piece only": they are dropped
        heroUpgrades =
            runCatching { JSONObject(result.read().orEmpty()) }.getOrNull()?.takeIf { it.optInt("format") == HERO_UPGRADES_FORMAT }?.let { o ->
                b?.let { bx ->
                    runCatching {
                        HeroUpgradeState(
                            o.getLong("scan"), o.getString("key"), HeroUpgrades.Result.of(o.getJSONObject("result"), bx.itemsById),
                            running = false, finishedAt = o.getLong("finishedAt"), attempt = o.optInt("attempt"),
                        )
                    }.getOrNull()
                }
            }
        resumable =
            runCatching { JSONObject(progress.read().orEmpty()).let { !it.getBoolean("finished") && it.getString("light") == key() } }
                .getOrDefault(false)
    }
}
