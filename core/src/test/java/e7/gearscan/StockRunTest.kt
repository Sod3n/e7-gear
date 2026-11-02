package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.data.GearStat
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Presets
import e7.gearscan.data.Request
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat
import e7.gearscan.opt.Objectives
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Gear stock per hero setup: for every slot, the pieces whose best build stays within E7_STOCK_EPS rolls of the
 * setup's best build, best first. Each setup searches alone over the whole box (no tiers, no ghosts), so this is what
 * the box can give that setup, not what the pool run assigns.
 *
 * Removing a slot's best piece and searching again gives the next piece in that slot by its own best build, so the
 * chain lists exactly the slot's pieces within reach, up to E7_STOCK_DEPTH.
 *
 * Setups come from the backup's hero configs, or with E7_STOCK_POP (a folder of Fribbels build files, one per hero
 * code) from community builds of every hero, turned into requests the way E7 Gear turns a preset into one: Most used
 * clusters with at least E7_STOCK_POP_SHARE percent (10) plus the top Top ranked and Fastest cluster, near-duplicates
 * (same sets and mains, targets within 2 rolls of investment) dropped.
 *
 * Needs E7_STOCK_BACKUP (configs, tiers, box) and E7_STOCK_OUT (JSON lines, one per setup; setups already in it are
 * skipped, so a rerun resumes). Optional: E7_STOCK_BOX (a gear.txt to search instead of the backup's box; heroes
 * missing from it come from the backup), E7_STOCK_HEROES (ids), E7_STOCK_EXCLUDE (file of item ids taken out of the
 * box), E7_STOCK_DEPTH (10), E7_STOCK_EPS (1 roll), E7_STOCK_MODS (0), E7_STOCK_THREADS (6), E7_STOCK_MS (20000),
 * E7_STOCK_BUDGET_S (stop starting setups after this many seconds; rerun to continue).
 *
 * E7_STOCK_JOBS (eval): JSON lines {tag, hero, allow: [item ids]} -> one line per tag with the hero's best build over
 * only the allowed items (or {exclude: [...]}: over the box without them).
 */
class StockRunTest {
    private val content: Content get() = TestContent.content
    private val threads = System.getenv("E7_STOCK_THREADS")?.toInt() ?: 6
    private val limitMs = System.getenv("E7_STOCK_MS")?.toLong() ?: 20_000L

    private class Ctx(
        val backup: JSONObject,
    ) {
        val st: JSONObject = backup.getJSONObject("state")
        val backupBox = Box(backup.getJSONObject("box"))
        val box = System.getenv("E7_STOCK_BOX")?.let { Box.parse(File(it).readText()) } ?: backupBox
        val tiers = st.getJSONObject("tiers").let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }
        val configs =
            st.getJSONObject("configs").let { c ->
                c.keys().asSequence().associate {
                    it.toLong() to
                        HeroConfig.fromJson(c.getJSONObject(it))
                }
            }

        fun heroOf(id: Long): OwnedHero? = box.hero(id) ?: backupBox.hero(id)
    }

    private fun ctx(): Ctx? = System.getenv("E7_STOCK_BACKUP")?.let { Ctx(JSONObject(File(it).readText())) }

    private fun ids(path: String?): Set<Long> =
        path?.takeIf { it.isNotEmpty() }?.let { f ->
            File(f)
                .readText()
                .split(Regex("[^0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.toLong() }
                .toSet()
        } ?: emptySet()

    private fun Request.alone(mods: Boolean) {
        lowerPriorityOnly = false
        keepCurrent = false
        maxChanges = 6
        this.mods = mods
    }

    private class Searcher(
        val h: OwnedHero,
        val cfg: HeroConfig,
        val req: Request,
        val box: Box,
        val content: Content,
        val base: Set<Long>,
        val threads: Int,
        val limitMs: Long,
    ) {
        var searches = 0
        var timeouts = 0

        fun setup(excl: Set<Long>) = Setup(h, content.heroes.getValue(h.code), cfg, req, box, content, { true }, base + excl)

        val hb = setup(emptySet()).hb

        fun search(
            excl: Set<Long>,
            threshold: Double = Double.NEGATIVE_INFINITY,
        ): e7.gearscan.opt.Build? {
            val r = SearchRun(setup(excl), 1, startThreshold = threshold)
            r.run(threads, limitMs)
            searches++
            if (r.engine.timedOut) timeouts++
            return r.builds().firstOrNull()
        }

        /** One roll of the objective: the mean weight of the targeted stats, or a speed roll. */
        fun roll(): Double =
            when (req.goal) {
                Goal.TARGETS -> (0 until 8).filter { !req.targets[it].isNaN() }.map { maxOf(1, req.weights[it]) }.average()
                Goal.SPEED -> Objectives.rollSize(hb)[3]
                else -> Double.NaN
            }
    }

    private fun chains(
        s: Searcher,
        line: JSONObject,
        depth: Int,
        epsRolls: Double,
    ) {
        val t0 = System.nanoTime()
        val best = s.search(emptySet())
        val roll = s.roll()
        if (best == null) {
            line.put("best", JSONArray())
        } else {
            val v0 = best.value
            val reach = if (roll.isNaN()) 0.02 * kotlin.math.abs(v0) else epsRolls * roll
            line
                .put("v0", v0)
                .put("roll", if (roll.isNaN()) JSONObject.NULL else roll)
                .put("eps", reach)
                .put("best", JSONArray(best.items.map { it.id }))
                .put(
                    "final",
                    JSONArray(
                        best.final.stats
                            .take(8)
                            .map { Math.round(it) },
                    ),
                )
            val slots = JSONObject()
            for (sl in Slot.entries) {
                val excl = HashSet<Long>()
                val chain = JSONArray()
                var b = best
                while (b != null && chain.length() < depth) {
                    val p = b.items.first { it.slot == sl }
                    chain.put(JSONArray().put(p.id).put(v0 - b.value))
                    excl += p.id
                    if (chain.length() == depth) break
                    b = s.search(excl, v0 - reach - 1e-9)?.takeIf { v0 - it.value <= reach + 1e-9 }
                }
                slots.put(sl.key, chain)
            }
            line.put("slots", slots)
        }
        line.put("searches", s.searches).put("timeouts", s.timeouts).put("ms", (System.nanoTime() - t0) / 1_000_000)
    }

    @Test
    fun stock() {
        val c = ctx()
        assumeTrue(c != null && System.getenv("E7_STOCK_JOBS") == null)
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val excludedIds = ids(System.getenv("E7_STOCK_EXCLUDE"))
        val depth = System.getenv("E7_STOCK_DEPTH")?.toInt() ?: 10
        val epsRolls = System.getenv("E7_STOCK_EPS")?.toDouble() ?: 1.0
        val mods = System.getenv("E7_STOCK_MODS") == "1"
        val done =
            if (out.exists()) {
                out
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map {
                        JSONObject(
                            it,
                        ).getString("key")
                    }.toSet()
            } else {
                emptySet()
            }

        val jobs =
            System.getenv("E7_STOCK_POP")?.let { population(File(it), log) } ?: run {
                val only =
                    System
                        .getenv("E7_STOCK_HEROES")
                        ?.split(",")
                        ?.filter { it.isNotBlank() }
                        ?.map { it.trim().toLong() }
                        ?.toSet()
                c.configs
                    .filter { (id, cfg) ->
                        val r = cfg.request ?: return@filter false
                        val h = c.heroOf(id) ?: return@filter false
                        (only == null || id in only) &&
                            content.heroes[h.code] != null &&
                            (r.goal != Goal.TARGETS || r.targets.any { !it.isNaN() })
                    }.keys
                    .sortedWith(compareByDescending<Long> { c.tiers[it] ?: 0 }.thenBy { it })
                    .map { id ->
                        val cfg = c.configs.getValue(id)
                        PopJob(
                            id.toString(),
                            c.heroOf(id)!!,
                            cfg,
                            cfg.request!!,
                            JSONObject().put("hero", id).put("tier", c.tiers[id] ?: 0),
                        )
                    }
            }
        log.appendText(
            "start ${jobs.size} setups, ${done.size} done, depth $depth, eps $epsRolls rolls, mods $mods, box ${c.box.items.size} items, excluded ${excludedIds.size}\n",
        )
        val stopAt = System.getenv("E7_STOCK_BUDGET_S")?.let { System.nanoTime() + it.toLong() * 1_000_000_000 }
        for (j in jobs) {
            if (j.key in done) continue
            if (stopAt != null && System.nanoTime() > stopAt) {
                log.appendText("budget used, rerun to continue\n")
                return
            }
            j.req.alone(mods)
            val s = Searcher(j.h, j.cfg, j.req, c.box, content, excludedIds, threads, limitMs)
            val line =
                JSONObject(j.info.toString())
                    .put("key", j.key)
                    .put("code", j.h.code)
                    .put("name", content.heroes[j.h.code]?.name ?: j.h.code)
                    .put("goal", j.req.goal.name)
            chains(s, line, depth, epsRolls)
            out.appendText(line.toString() + "\n")
            log.appendText(
                "%s %s: %d searches, %d timeouts, %.1f s\n".format(
                    j.key,
                    line.getString("name"),
                    s.searches,
                    s.timeouts,
                    line.getLong("ms") / 1000.0,
                ),
            )
        }
        log.appendText("finished\n")
    }

    private class PopJob(
        val key: String,
        val h: OwnedHero,
        val cfg: HeroConfig,
        val req: Request,
        val info: JSONObject,
    )

    private fun population(
        dir: File,
        log: File,
    ): List<PopJob> {
        val minShare = System.getenv("E7_STOCK_POP_SHARE")?.toInt() ?: 10
        val seen = HashSet<String>()
        val out = ArrayList<PopJob>()
        var raw = 0
        val files = dir.listFiles { f -> f.name.matches(Regex("c\\w+\\.json")) }!!.sortedBy { it.name }
        for ((fi, f) in files.withIndex()) {
            val code = f.name.removeSuffix(".json")
            val game = content.heroes[code] ?: continue
            val tabs = runCatching { Presets.parse(JSONObject(f.readText()), content) }.getOrNull() ?: continue
            val picks =
                tabs.mostUsed.filter { it.share >= minShare }.map { "Most used" to it } +
                    listOfNotNull(
                        tabs.topRanked.firstOrNull()?.let { "Top ranked" to it },
                        tabs.fastest.firstOrNull()?.let {
                            "Fastest" to
                                it
                        },
                    )
            val h = OwnedHero(-1L - fi, code, game.name ?: code, 6)
            for ((tab, p) in picks) {
                raw++
                val cfg = HeroConfig()
                val hb =
                    e7.gearscan.calc.Calc
                        .heroBase(game, cfg, p.artifact, content)
                val req = Presets.toRequest(p, hb, null, tab)
                if (req.targets.all { it.isNaN() }) continue
                val roll = Objectives.rollSize(hb)
                val floor = hb.fixed.copyOf()
                floor[0] += 525 * hb.mult[0]
                floor[1] += 2835 * hb.mult[1]
                floor[2] += 310 * hb.mult[2]
                val sig =
                    req.setAlts.joinToString { it.toSortedMap().toString() } + "|" + req.mains.toSortedMap().toString() + "|" +
                        (0 until 8).joinToString(",") { x ->
                            if (req.targets[x].isNaN()) {
                                "-"
                            } else {
                                "${Math.round(
                                    (req.targets[x] - floor[x]) / roll[x] / 2,
                                )}:${req.weights[x]}"
                            }
                        }
                if (!seen.add(sig)) continue
                val info =
                    JSONObject()
                        .put("tab", tab)
                        .put("label", p.label)
                        .put("share", p.share)
                        .put("sig", sig)
                        .put("targets", JSONArray(req.targets.map { if (it.isNaN()) JSONObject.NULL else it }))
                        .put("weights", JSONArray(req.weights.toList()))
                out += PopJob("$code|$tab|${p.label}", h, cfg, req, info)
            }
        }
        log.appendText("population: ${files.size} files, $raw presets, ${out.size} after dropping near-duplicates\n")
        return out
    }

    @Test
    fun eval() {
        val c = ctx()
        val jobsPath = System.getenv("E7_STOCK_JOBS")
        assumeTrue(c != null && jobsPath != null)
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val mods = System.getenv("E7_STOCK_MODS") == "1"
        val bestOf = HashMap<String, List<Long>>()
        if (out.exists()) {
            out.readLines().filter { it.isNotBlank() }.map(::JSONObject).forEach { l ->
                bestOf[l.getString("tag")] = l.getJSONArray("best").let { a -> (0 until a.length()).map(a::getLong) }
            }
        }
        val done = bestOf.keys.toSet()
        val all =
            c.box.items
                .map { it.id }
                .toSet()
        for (raw in File(jobsPath!!).readLines()) {
            if (raw.isBlank()) continue
            val j = JSONObject(raw)
            val tag = j.getString("tag")
            if (tag in done) continue
            val id = j.getLong("hero")
            val h = c.heroOf(id)!!
            val cfg = c.configs.getValue(id)
            val req = cfg.request!!.copy().also { it.alone(mods) }

            fun longs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getLong).toSet() }
            val excl =
                (longs("allow")?.let { all - it } ?: longs("exclude") ?: emptySet()) +
                    (j.optString("minusBestOf").takeIf { it.isNotEmpty() }?.let { bestOf[it] } ?: emptyList())
            val s = Searcher(h, cfg, req, c.box, content, excl, threads, limitMs)
            val t0 = System.nanoTime()
            val b = s.search(emptySet())
            val line =
                JSONObject()
                    .put("tag", tag)
                    .put("hero", id)
                    .put("roll", s.roll().let { if (it.isNaN()) JSONObject.NULL else it })
                    .put("v", b?.value ?: JSONObject.NULL)
                    .put("best", JSONArray(b?.items?.map { it.id } ?: emptyList<Long>()))
                    .put("timeouts", s.timeouts)
                    .put("ms", (System.nanoTime() - t0) / 1_000_000)
            out.appendText(line.toString() + "\n")
            bestOf[tag] = b?.items?.map { it.id } ?: emptyList()
        }
    }

    /**
     * E7_STOCK_SYNTH=N: N made-up setups, each crossing two community builds of different heroes stat by stat (the
     * first parent's hero and artifact; each stat's investment in rolls and weight from either parent; sets from either).
     * Each is searched over the box without E7_STOCK_EXCLUDE (spare) and over the whole box (alone), before and after
     * selling everything finished outside E7_STOCK_KEEP and E7_STOCK_EXCLUDE.
     */
    @Test
    fun synth() {
        val c = ctx()
        val n = System.getenv("E7_STOCK_SYNTH")?.toInt()
        assumeTrue(c != null && n != null)
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val reserved = ids(System.getenv("E7_STOCK_EXCLUDE"))
        val keep = ids(System.getenv("E7_STOCK_KEEP"))
        val all =
            c.box.items
                .map { it.id }
                .toSet()
        val done =
            if (out.exists()) {
                out
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map {
                        JSONObject(
                            it,
                        ).getString("key")
                    }.toSet()
            } else {
                emptySet()
            }

        class Inv(
            val job: PopJob,
            val floor: DoubleArray,
            val roll: DoubleArray,
            val inv: DoubleArray,
        )
        val invs =
            population(File(System.getenv("E7_STOCK_POP")!!), log).map { j ->
                val hb =
                    e7.gearscan.calc.Calc
                        .heroBase(content.heroes.getValue(j.h.code), j.cfg, j.req.artifact, content)
                val roll = Objectives.rollSize(hb)
                val floor = hb.fixed.copyOf()
                floor[0] += 525 * hb.mult[0]
                floor[1] += 2835 * hb.mult[1]
                floor[2] += 310 * hb.mult[2]
                Inv(j, floor, roll, DoubleArray(8) { x -> (j.req.targets[x] - floor[x]) / roll[x] })
            }
        val rnd = kotlin.random.Random(System.getenv("E7_STOCK_SEED")?.toInt() ?: 1)
        for (i in 0 until n!!) {
            var a: Inv
            var b: Inv
            var pick: BooleanArray
            do {
                a = invs.random(rnd)
                b = invs.random(rnd)
                pick = BooleanArray(8) { rnd.nextBoolean() }
                val fromA = (0 until 8).count { pick[it] && !a.inv[it].isNaN() }
                val fromB = (0 until 8).count { !pick[it] && !b.inv[it].isNaN() }
            } while (a.job.h.code == b.job.h.code || fromA == 0 || fromB == 0 || fromA + fromB < 2)
            val setsFromA = rnd.nextBoolean()
            val key = "synth-$i"
            if (key in done) continue
            val req = Request()
            req.artifact = a.job.req.artifact
            req.setAlts = (if (setsFromA) a else b).job.req.setAlts
            for (x in 0 until 8) {
                val src = if (pick[x]) a else b
                if (src.inv[x].isNaN()) continue
                val t = a.floor[x] + src.inv[x] * a.roll[x]
                req.targets[x] = if (x == e7.gearscan.data.Stat.CR.ordinal) minOf(t, 100.0) else Math.round(t).toDouble()
                req.weights[x] = src.job.req.weights[x]
            }
            req.mains =
                Slot.ACCESSORIES
                    .associateWith { slot ->
                        val opts =
                            e7.gearscan.opt.Ghost.ACCESSORY_MAINS.getValue(slot).filter {
                                it.percentOfBase ||
                                    it.stat.pct ||
                                    it == e7.gearscan.data.GearStat.SPD
                            }
                        val best = opts.maxOf { req.weights[it.stat.ordinal] }
                        if (best < 2) emptySet() else opts.filter { req.weights[it.stat.ordinal] == best }.take(2).toSet()
                    }.filterValues { it.isNotEmpty() }
            req.alone(false)
            val line =
                JSONObject()
                    .put("key", key)
                    .put("a", a.job.key)
                    .put("b", b.job.key)
                    .put("setsFrom", if (setsFromA) "a" else "b")
                    .put(
                        "targets",
                        JSONArray(req.targets.map { if (it.isNaN()) JSONObject.NULL else it }),
                    ).put("weights", JSONArray(req.weights.toList()))
            for ((arm, base) in listOf(
                "spareBase" to reserved,
                "spareSold" to all - keep,
                "aloneBase" to emptySet(),
                "aloneSold" to all - (reserved + keep),
            )) {
                val s = Searcher(a.job.h, a.job.cfg, req, c.box, content, base, threads, limitMs)
                line.put("roll", s.roll())
                line.put(arm, s.search(emptySet())?.value ?: JSONObject.NULL)
            }
            out.appendText(line.toString() + "\n")
        }
        log.appendText("synth finished\n")
    }

    private class Spare(
        val key: String,
        val job: PopJob,
        val best: List<Long>,
        val v0: Double,
        val slots: JSONObject,
        val roll: Double = Double.NaN,
        val own: Boolean = false,
    )

    /**
     * E7_STOCK_SWAP=1: with each setup's best spare build from the chain files E7_STOCK_CHAINS (rebuilt from the backup
     * configs or E7_STOCK_POP), swaps candidates into it one slot at a time: E7_STOCK_SIMS simulated +15 finishes of
     * every piece below +12 (Lv85/88 roll ranges, equal chance per line, Heroic 4th line at +12), and one-stone mod
     * versions of the pieces in E7_STOCK_SOLD (greater stone, typical roll, lines with up to 2 rolls, a roll's cost).
     * A candidate counts when it beats the setup's best spare build. Also checks the swap against the chains' own
     * runner-up pieces (where a swap underrates a piece, the search found a better rearrangement).
     */
    @Test
    fun swap() {
        val c = ctx()
        assumeTrue(c != null && System.getenv("E7_STOCK_SWAP") == "1")
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val spares = loadSpares(c, log)
        val byId = c.box.itemsById
        val raw = JSONObject(File(System.getenv("E7_STOCK_BOX")!!).readText()).getJSONArray("items")
        val opMain =
            (0 until raw.length()).associate { i ->
                raw.getJSONObject(i).let { o ->
                    o.getLong("id") to
                        o.getJSONArray("op").getJSONArray(0).getDouble(1)
                }
            }
        val nSims = System.getenv("E7_STOCK_SIMS")?.toInt() ?: 30
        val unfinished =
            c.box.items.filter {
                it.enhance < 12 &&
                    it.level >= 85 &&
                    (it.rank == "Epic" || it.rank == "Heroic") &&
                    content.setIndex(it.set) >= 0
            }
        val sims =
            unfinished.map { it ->
                val m = opMain.getValue(it.id).let { v -> if (it.mainType.showsPercent) v * 100 * 5 else Math.floor(v * 5) }
                finishes(it, m, nSims, kotlin.random.Random(it.id.hashCode()))
            }
        val flat = sims.flatten()
        val firstHit =
            java.util.concurrent.atomic
                .AtomicIntegerArray(flat.size)
                .also { a -> for (i in 0 until a.length()) a.set(i, -1) }
        val bySlot = Slot.entries.associateWith { s -> flat.indices.filter { flat[it].slot == s } }
        val sold = ids(System.getenv("E7_STOCK_SOLD")).mapNotNull { byId[it] }
        val modHits = java.util.concurrent.ConcurrentHashMap<Long, MutableList<String>>()
        val check = java.util.concurrent.ConcurrentLinkedQueue<DoubleArray>()
        val t0 = System.nanoTime()
        val maxDiff =
            java.util.concurrent.atomic
                .AtomicReference(0.0)
        spares.indices.toList().parallelStream().forEach { si ->
            val s = spares[si]
            val req = s.job.req
            val setup = Setup(s.job.h, content.heroes.getValue(s.job.h.code), s.job.cfg, req, c.box, content, { true })
            val hb = setup.hb
            val obj = Objectives.of(req, hb)
            val maxs = setup.maxs()

            fun value(items: List<Item>): Double {
                for (it in items) {
                    val m = req.mains[it.slot]
                    if (!m.isNullOrEmpty() &&
                        it.mainType !in m
                    ) {
                        return Double.NEGATIVE_INFINITY
                    }
                }
                if (req.setAlts.isNotEmpty()) {
                    val cnt = items.groupingBy { it.set }.eachCount()
                    if (req.setAlts.none { alt -> alt.all { (k, n) -> (cnt[k] ?: 0) >= n } }) return Double.NEGATIVE_INFINITY
                }
                val f =
                    e7.gearscan.calc.Calc
                        .final(hb, items, req.reforged, content)
                for (x in 0 until 8) {
                    if (!req.mins[x].isNaN() && f.stats[x] < req.mins[x]) return Double.NEGATIVE_INFINITY
                    if (!maxs[x].isNaN() && f.stats[x] > maxs[x]) return Double.NEGATIVE_INFINITY
                }
                return obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
            }
            val b = s.best.map { byId.getValue(it) }
            val d = kotlin.math.abs(value(b) - s.v0)
            maxDiff.accumulateAndGet(d) { x, y -> maxOf(x, y) }
            val wanted = setup.modWanted()
            for (sl in Slot.entries) {
                val others = b.filter { it.slot != sl }
                val ch = s.slots.getJSONArray(sl.key)
                for (k in 1 until ch.length()) {
                    val e = ch.getJSONArray(k)
                    check += doubleArrayOf(si.toDouble(), e.getDouble(1), s.v0 - value(others + byId.getValue(e.getLong(0))))
                }
                for (idx in bySlot.getValue(sl)) {
                    if (firstHit.get(idx) >= 0) continue
                    if (value(others + flat[idx]) > s.v0 + 1e-9) firstHit.compareAndSet(idx, -1, si)
                }
                for (it in sold) {
                    if (it.slot != sl) continue
                    val hit =
                        e7.gearscan.opt.Mods
                            .variants(it, wanted, { t -> t !in wanted }, 2, true, 0.5, req.reforged)
                            .any { v -> value(others + v) - setup.modCost > s.v0 + 1e-9 }
                    if (hit) modHits.computeIfAbsent(it.id) { java.util.Collections.synchronizedList(ArrayList()) }.add(s.key)
                }
            }
        }
        var off = 0
        out.writeText("")
        for ((pi, it) in unfinished.withIndex()) {
            val hits = (off until off + sims[pi].size).map { firstHit.get(it) }.filter { it >= 0 }
            off += sims[pi].size
            out.appendText(
                JSONObject()
                    .put("kind", "sim")
                    .put("id", it.id)
                    .put("n", sims[pi].size)
                    .put("enter", hits.size)
                    .put("gs", JSONArray(sims[pi].map { s -> Math.round(s.score * 10) / 10.0 }))
                    .put(
                        "for",
                        JSONArray(
                            hits
                                .groupingBy {
                                    spares[it].key
                                }.eachCount()
                                .entries
                                .sortedByDescending { it.value }
                                .take(3)
                                .map { it.key },
                        ),
                    ).toString() +
                    "\n",
            )
        }
        for (it in sold) {
            out.appendText(
                JSONObject()
                    .put("kind", "mod")
                    .put("id", it.id)
                    .put("for", JSONArray(modHits[it.id]?.take(5) ?: emptyList<String>()))
                    .put("count", modHits[it.id]?.size ?: 0)
                    .toString() + "\n",
            )
        }
        for (r in check) {
            out.appendText(
                JSONObject()
                    .put(
                        "kind",
                        "check",
                    ).put(
                        "setup",
                        spares[r[0].toInt()].key,
                    ).put("chainGap", r[1])
                    .put("swapGap", if (r[2].isFinite()) r[2] else JSONObject.NULL)
                    .toString() +
                    "\n",
            )
        }
        log.appendText(
            "swap: ${spares.size} setups, ${flat.size} simulated pieces, ${sold.size} sold pieces, max |value - v0| ${maxDiff.get()}, %.1f s\n"
                .format(
                    (System.nanoTime() - t0) / 1e9,
                ),
        )
    }

    /**
     * E7_STOCK_USEFUL=1: for every piece below +12 (not worn by E7_STOCK_EXCLUDE heroes), E7_STOCK_SIMS simulated +15
     * finishes. A finish gains for a setup when the setup's best build with it in its slot beats the setup's best spare
     * build (chain files E7_STOCK_CHAINS; spares = box without E7_STOCK_EXCLUDE) by more than 0.05 roll. Cheap proofs
     * first: no legal place (main, sets); a same-set spare in that slot at least as good in every stat the setup scores,
     * or a price bound (what the finish has over a same-set spare, at each stat's full weight per roll) below the bar
     * (cannot beat); the finish swapped into the best spare build (beats). The rest get the full search with the finish as
     * the slot's only piece, capped at E7_STOCK_EXACT_MS (1000); a capped search that found nothing counts as unknown.
     * Your setups (chain lines with "hero") are all checked; community setups only until the finish gains a roll somewhere.
     * E7_STOCK_PASS (own,community) picks the setups; E7_STOCK_TASK_MS (10000) caps the searches per setup and slot, after which the
     * rest count as unknown. E7_STOCK_ONLY (file of piece ids), E7_STOCK_MIN_GAIN (0.05 rolls), E7_STOCK_ENOUGH (community
     * setups stop checking a piece after this many of its finishes gained). Pieces go in chunks of E7_STOCK_CHUNK (50), each written when done, so a rerun resumes. Per chunk, a few
     * "cannot beat" calls are rechecked by full search (logged).
     */
    @Test
    fun useful() {
        val c = ctx()
        assumeTrue(c != null && System.getenv("E7_STOCK_USEFUL") == "1")
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val reserved = ids(System.getenv("E7_STOCK_EXCLUDE"))
        val spares = loadSpares(c, log)
        val byId = c.box.itemsById
        val raw = JSONObject(File(System.getenv("E7_STOCK_BOX")!!).readText()).getJSONArray("items")
        val opMain =
            (0 until raw.length()).associate { i ->
                raw.getJSONObject(i).let { o ->
                    o.getLong("id") to
                        o.getJSONArray("op").getJSONArray(0).getDouble(1)
                }
            }
        val nSims = System.getenv("E7_STOCK_SIMS")?.toInt() ?: 30
        val limit = System.getenv("E7_STOCK_UNF_LIMIT")?.toInt() ?: Int.MAX_VALUE
        val chunk = System.getenv("E7_STOCK_CHUNK")?.toInt() ?: 50
        val exactMs = System.getenv("E7_STOCK_EXACT_MS")?.toLong() ?: 1000L
        val perChunkCheck = System.getenv("E7_STOCK_VALIDATE")?.toInt() ?: 10
        val taskMs = System.getenv("E7_STOCK_TASK_MS")?.toLong() ?: 10_000L
        val passes = (System.getenv("E7_STOCK_PASS") ?: "own,community").split(",").toSet()
        val done =
            if (out.exists()) {
                out
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map { JSONObject(it).getLong("id") }
                    .toSet()
            } else {
                emptySet()
            }
        val only = System.getenv("E7_STOCK_ONLY")?.let { ids(it) }
        val minGain = System.getenv("E7_STOCK_MIN_GAIN")?.toDouble() ?: 0.05
        val enough = System.getenv("E7_STOCK_ENOUGH")?.toInt() ?: Int.MAX_VALUE
        val unfinished =
            c.box.items
                .filter {
                    it.enhance < 12 &&
                        it.level >= 85 &&
                        (it.rank == "Epic" || it.rank == "Heroic") &&
                        content.setIndex(it.set) >= 0 &&
                        it.id !in reserved &&
                        (only == null || it.id in only)
                }.sortedBy { it.id.hashCode() }
                .take(limit)
        val n =
            java.util.concurrent.atomic
                .AtomicLongArray(11)
        val t0 = System.nanoTime()

        class Ctx2(
            val s: Spare,
        ) {
            val req = s.job.req

            // your heroes keep their own worn pieces: the bar is their best build without taking from other pool heroes
            val excl =
                if (s.own) {
                    reserved -
                        c.box
                            .gearOf(s.job.h.id)
                            .values
                            .map { it.id }
                            .toSet()
                } else {
                    reserved
                }
            val setup = Setup(s.job.h, content.heroes.getValue(s.job.h.code), s.job.cfg, req, c.box, content, { true }, excl)
            val hb = setup.hb
            val obj = Objectives.of(req, hb)
            val maxs = setup.maxs()
            val roll = if (s.roll.isNaN()) 0.01 * kotlin.math.abs(s.v0) else s.roll
            val b = s.best.map { byId.getValue(it) }
            val cares = ((obj.uses.filter { it < 8 }) + (0 until 8).filter { !req.mins[it].isNaN() }).distinct().toIntArray()

            // most one unit of each stat can add to a TARGETS value (its weight per roll); null: other goals, dominance only
            val slope: DoubleArray? =
                if (req.goal == Goal.TARGETS) {
                    Objectives.rollSize(hb).let { r ->
                        DoubleArray(8) { i -> if (req.targets[i].isNaN()) 0.0 else maxOf(1, req.weights[i]) / r[i] }
                    }
                } else {
                    null
                }
            val minIdx = (0 until 8).filter { !req.mins[it].isNaN() }
            val fullAlts = req.setAlts.isNotEmpty() && req.setAlts.all { it.values.sum() >= 6 }
            val altSets = req.setAlts.flatMap { it.keys }.toSet()
            val spareVecs = HashMap<Pair<Slot, String>, ArrayList<DoubleArray>>()
            val sparesBySlotSet = HashMap<Pair<Slot, String>, ArrayList<Item>>()

            init {
                for (it in c.box.items) {
                    if (setup.eligible(it)) {
                        spareVecs.getOrPut(it.slot to it.set) { ArrayList() }.add(
                            e7.gearscan.calc.Calc
                                .itemVec(it, hb, req.reforged),
                        )
                        sparesBySlotSet.getOrPut(it.slot to it.set) { ArrayList() }.add(it)
                    }
                }
            }

            val slotSetups = java.util.concurrent.ConcurrentHashMap<Slot, Setup>()

            fun slotSetup(sl: Slot) =
                slotSetups.getOrPut(sl) {
                    Setup(
                        s.job.h,
                        content.heroes.getValue(s.job.h.code),
                        s.job.cfg,
                        req,
                        c.box,
                        content,
                        { true },
                        excl +
                            c.box.items
                                .filter { it.slot == sl }
                                .map { it.id },
                    )
                }

            fun value(items: List<Item>): Double {
                if (req.setAlts.isNotEmpty()) {
                    val cnt = items.groupingBy { it.set }.eachCount()
                    if (req.setAlts.none { alt -> alt.all { (k, m) -> (cnt[k] ?: 0) >= m } }) return Double.NEGATIVE_INFINITY
                }
                val f =
                    e7.gearscan.calc.Calc
                        .final(hb, items, req.reforged, content)
                for (x in 0 until 8) {
                    if (!req.mins[x].isNaN() && f.stats[x] < req.mins[x]) return Double.NEGATIVE_INFINITY
                    if (!maxs[x].isNaN() && f.stats[x] > maxs[x]) return Double.NEGATIVE_INFINITY
                }
                return obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
            }

            /** Best value above thr with q as the slot's only piece; null when none; NaN when the cap ran out first. */
            fun exact(
                q: Item,
                thr: Double,
                ms: Long,
            ): Double? {
                val r = SearchRun(slotSetup(q.slot), 1, extra = listOf(q), startThreshold = thr)
                r.run(1, ms)
                val v =
                    r
                        .builds()
                        .firstOrNull()
                        ?.value
                        ?.takeIf { it > thr }
                if (v == null && r.engine.timedOut) {
                    n.incrementAndGet(7)
                    return Double.NaN
                }
                return v
            }

            /** The value found with q in its slot above thr; null: proved it cannot beat thr; NaN: unknown. 2nd: a "cannot beat" proof. */
            fun check(
                q: Item,
                thr: Double,
            ): Pair<Double?, Boolean> {
                n.incrementAndGet(0)
                val m = req.mains[q.slot]
                if (!m.isNullOrEmpty() && q.mainType !in m) {
                    n.incrementAndGet(1)
                    return null to false
                }
                if (fullAlts && q.set !in altSets) {
                    n.incrementAndGet(1)
                    return null to false
                }
                val vq =
                    e7.gearscan.calc.Calc
                        .itemVec(q, hb, req.reforged)
                val same = spareVecs[q.slot to q.set].orEmpty()
                if (same.any { vp -> cares.all { vp[it] >= vq[it] - 1e-9 } }) {
                    n.incrementAndGet(2)
                    return null to true
                }
                // swapping q for a same-set spare p gives a spare build, so q's best is at most v0 + what q has over p, priced at full slope
                if (slope != null) {
                    val bound =
                        same
                            .filter { vp -> minIdx.all { vp[it] >= vq[it] - 1e-9 } }
                            .minOfOrNull { vp ->
                                (0 until 8).sumOf { i -> slope[i] * maxOf(0.0, vq[i] - vp[i]) } +
                                    1e-4 * maxOf(0.0, vq[8] - vp[8])
                            }
                    if (bound != null && s.v0 + bound <= thr) {
                        n.incrementAndGet(8)
                        return null to true
                    }
                }
                val swapped = b.map { if (it.slot == q.slot) q else it }
                val sw = value(swapped)
                if (sw > thr) {
                    n.incrementAndGet(3)
                    return sw to false
                }
                if (sw == Double.NEGATIVE_INFINITY && req.setAlts.isNotEmpty()) {
                    // the plain swap broke a set: refill one other slot with a spare of a set the alt is short of
                    val cnt = swapped.groupingBy { it.set }.eachCount()
                    for (alt in req.setAlts) {
                        val short = alt.filter { (k, m) -> (cnt[k] ?: 0) < m }
                        if (short.size != 1 || short.values.first() - (cnt[short.keys.first()] ?: 0) != 1) continue
                        val need = short.keys.first()
                        for (j in swapped.indices) {
                            val old = swapped[j]
                            if (old.slot == q.slot || (alt[old.set] ?: 0) > 0 && (cnt[old.set] ?: 0) <= alt.getValue(old.set)) continue
                            for (p in sparesBySlotSet[old.slot to need].orEmpty()) {
                                val v = value(swapped.mapIndexed { k, it -> if (k == j) p else it })
                                if (v > thr) {
                                    n.incrementAndGet(6)
                                    return v to false
                                }
                            }
                        }
                    }
                }
                n.incrementAndGet(4)
                return Double.POSITIVE_INFINITY to false
            }

            /**
             * Best value above thr for each of qs (all in slot sl) as the slot's only piece; -inf: none; NaN: unknown. One
             * search returns up to 200 builds above thr; while it is cut short (full or out of time), the finishes it
             * returned are set aside and the rest searched again, and a batch that times out with nothing is split in two.
             */
            fun batch(
                sl: Slot,
                qs: List<Item>,
                thr: Double,
                until: Long,
            ): Map<Long, Double> {
                val k = 200
                val out = HashMap<Long, Double>()
                var rest = qs
                while (rest.isNotEmpty()) {
                    if (System.nanoTime() > until) {
                        n.incrementAndGet(9)
                        rest.forEach { out[it.id] = Double.NaN }
                        break
                    }
                    n.incrementAndGet(5)
                    val r = SearchRun(slotSetup(sl), k, extra = rest, startThreshold = thr)
                    r.run(1, exactMs)
                    val builds = r.builds()
                    val found = HashMap<Long, Double>()
                    for (bd in builds) {
                        if (bd.value >
                            thr
                        ) {
                            bd.items.first { it.slot == sl }.let { g -> found.merge(g.id, bd.value) { a, c -> maxOf(a, c) } }
                        }
                    }
                    out.putAll(found)
                    if (!r.engine.timedOut && builds.size < k) {
                        rest.forEach { out.putIfAbsent(it.id, Double.NEGATIVE_INFINITY) }
                        break
                    }
                    n.incrementAndGet(7)
                    if (found.isEmpty()) {
                        if (rest.size == 1) {
                            out[rest[0].id] = Double.NaN
                            break
                        }
                        rest.chunked((rest.size + 1) / 2).forEach { h -> out.putAll(batch(sl, h, thr, until)) }
                        break
                    }
                    rest = rest.filter { it.id !in found }
                }
                return out
            }
        }
        // setups furthest from their targets first, so finishes that gain a roll somewhere are found early and skipped after
        val ctxs = spares.map { Ctx2(it) }.sortedBy { it.s.v0 / it.roll }
        log.appendText(
            "useful: ${unfinished.size} pieces (${done.size} done), $nSims sims, ${ctxs.size} setups, chunks of $chunk, exact cap $exactMs ms\n",
        )
        val todo = unfinished.filter { it.id !in done }
        for (part in todo.chunked(chunk)) {
            var nextId = -1L
            val owner = ArrayList<Int>()
            val flat = ArrayList<Item>()
            for ((pi, it) in part.withIndex()) {
                val m = opMain.getValue(it.id).let { v -> if (it.mainType.showsPercent) v * 100 * 5 else Math.floor(v * 5) }
                for (q in finishes(it, m, nSims, kotlin.random.Random(it.id.hashCode()))) {
                    flat += Item(nextId--, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null)
                    owner += pi
                }
            }
            val bySlot = Slot.entries.associateWith { s -> flat.indices.filter { flat[it].slot == s } }
            val flags =
                Array(6) {
                    java.util.concurrent.atomic
                        .AtomicIntegerArray(flat.size)
                } // own0 own1 any0 any1 ownUnknown anyUnknown
            val ownFor = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentHashMap<String, Int>>()
            val proofs = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int>>()
            val hits =
                java.util.concurrent.atomic
                    .AtomicIntegerArray(part.size)

            fun record(
                x: Ctx2,
                idx: Int,
                v: Double,
                own: Boolean,
            ) {
                if (v.isNaN()) {
                    flags[5].set(idx, 1)
                    if (own) flags[4].set(idx, 1)
                    return
                }
                val gain = (v - x.s.v0) / x.roll
                if (flags[2].get(idx) == 0) hits.incrementAndGet(owner[idx])
                flags[2].set(idx, 1)
                if (gain >= 1) flags[3].set(idx, 1)
                if (own) {
                    flags[0].set(idx, 1)
                    if (gain >= 1) flags[1].set(idx, 1)
                    ownFor.computeIfAbsent(owner[idx]) { java.util.concurrent.ConcurrentHashMap() }.merge(x.s.job.h.name, 1, Int::plus)
                }
            }

            fun pass(own: Boolean) =
                ctxs.indices
                    .filter { ctxs[it].s.own == own }
                    .flatMap { si -> Slot.entries.map { sl -> si to sl } }
                    .parallelStream()
                    .forEach { (si, sl) ->
                        val x = ctxs[si]
                        val base = x.s.v0 + minGain * x.roll
                        val amb = HashMap<Double, ArrayList<Int>>()
                        for (idx in bySlot.getValue(sl)) {
                            if (!own && flags[3].get(idx) == 1) continue
                            if (!own && hits.get(owner[idx]) >= enough) continue
                            val thr = if (!own && flags[2].get(idx) == 1) x.s.v0 + x.roll else base
                            val (v, proof) = x.check(flat[idx], thr)
                            if (proof && proofs.size < perChunkCheck && kotlin.random.Random.nextInt(2000) == 0) proofs += si to idx
                            when {
                                v == null -> {}
                                v == Double.POSITIVE_INFINITY -> amb.getOrPut(thr) { ArrayList() }.add(idx)
                                else -> record(x, idx, v, own)
                            }
                        }
                        val until = System.nanoTime() + taskMs * 1_000_000
                        for ((thr, idxs) in amb) {
                            val got = x.batch(sl, idxs.map { flat[it] }, thr, until)
                            for (idx in idxs) {
                                got.getValue(flat[idx].id).let { v ->
                                    if (v !=
                                        Double.NEGATIVE_INFINITY
                                    ) {
                                        record(x, idx, v, own)
                                    }
                                }
                            }
                        }
                    }
            if ("own" in passes) pass(true)
            if ("community" in passes) pass(false)
            val wrong =
                proofs
                    .toList()
                    .parallelStream()
                    .filter { (si, idx) ->
                        ctxs[si].exact(flat[idx], ctxs[si].s.v0 + 0.05 * ctxs[si].roll, 20_000).let { it != null && !it.isNaN() }
                    }.count()
            n.addAndGet(10, wrong)
            val sb = StringBuilder()
            for ((pi, it) in part.withIndex()) {
                val r = flat.indices.filter { owner[it] == pi }

                fun cnt(k: Int) = r.count { flags[k].get(it) == 1 }
                sb
                    .append(
                        JSONObject()
                            .put("id", it.id)
                            .put("n", r.size)
                            .put("own0", cnt(0))
                            .put("own1", cnt(1))
                            .put("any0", cnt(2))
                            .put("any1", cnt(3))
                            .put("ownUnknown", r.count { flags[4].get(it) == 1 && flags[0].get(it) == 0 })
                            .put("anyUnknown", r.count { flags[5].get(it) == 1 && flags[2].get(it) == 0 })
                            .put(
                                "ownFor",
                                JSONObject(
                                    ownFor[pi]
                                        ?.entries
                                        ?.sortedByDescending { it.value }
                                        ?.take(3)
                                        ?.associate { it.key to it.value }
                                        ?: emptyMap<String, Int>(),
                                ),
                            ).toString(),
                    ).append("\n")
            }
            out.appendText(sb.toString())
            log.appendText(
                (
                    "chunk of %d pieces: checks %d, no place %d, dominated %d, bound %d, swap beats %d, set-repair beats %d, left %d in %d batch searches (cut short %d, out of time %d); " +
                        "rechecked %d proofs, %d wrong; %.0f s\n"
                ).format(
                    part.size,
                    n.get(0),
                    n.get(1),
                    n.get(2),
                    n.get(8),
                    n.get(3),
                    n.get(6),
                    n.get(4),
                    n.get(5),
                    n.get(7),
                    n.get(9),
                    proofs.size,
                    wrong,
                    (System.nanoTime() - t0) / 1e9,
                ),
            )
        }
        log.appendText("useful finished\n")
    }

    /**
     * E7_STOCK_GROUPS=1: pieces that help only together. For each of your setups (E7_STOCK_CHAINS lines with "hero"; bar =
     * best build from own gear + spares), one typical finish (median gear score of E7_STOCK_SIMS) of every piece below
     * +12 is offered at once; while the best build beats the bar by a roll using some of them, it is recorded and those
     * finishes are taken out. A build using 2+ finishes is then checked with each finish alone: if none alone beats the
     * bar by a roll, the pieces help only together. With E7_STOCK_ROUNDS=R: R rounds, each with a fresh random finish of every
     * piece offered together (heroes in parallel); output lines per hero and round.
     */
    @Test
    fun groups() {
        val c = ctx()
        assumeTrue(c != null && System.getenv("E7_STOCK_GROUPS") == "1")
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val reserved = ids(System.getenv("E7_STOCK_EXCLUDE"))
        val spares = loadSpares(c, log).filter { it.own }
        val raw = JSONObject(File(System.getenv("E7_STOCK_BOX")!!).readText()).getJSONArray("items")
        val opMain =
            (0 until raw.length()).associate { i ->
                raw.getJSONObject(i).let { o ->
                    o.getLong("id") to
                        o.getJSONArray("op").getJSONArray(0).getDouble(1)
                }
            }
        val nSims = System.getenv("E7_STOCK_SIMS")?.toInt() ?: 30
        val unfinished =
            c.box.items.filter {
                it.enhance < 12 &&
                    it.level >= 85 &&
                    (it.rank == "Epic" || it.rank == "Heroic") &&
                    content.setIndex(it.set) >= 0 &&
                    it.id !in reserved
            }
        var nextId = -1L
        val repOf = HashMap<Long, Long>()
        val reps =
            unfinished.map { it ->
                val m = opMain.getValue(it.id).let { v -> if (it.mainType.showsPercent) v * 100 * 5 else Math.floor(v * 5) }
                val q = finishes(it, m, nSims, kotlin.random.Random(it.id.hashCode())).sortedBy { f -> f.score }[nSims / 2]
                Item(nextId--, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null).also { r ->
                    repOf[r.id] = it.id
                }
            }
        val done =
            if (out.exists()) {
                out
                    .readLines()
                    .filter { it.isNotBlank() }
                    .map {
                        JSONObject(
                            it,
                        ).getString("key")
                    }.toSet()
            } else {
                emptySet()
            }
        val rounds = System.getenv("E7_STOCK_ROUNDS")?.toInt() ?: 0
        if (rounds > 0) {
            // each round: a fresh random finish of every piece, all offered together; record who ends up in improving builds
            for (round in 0 until rounds) {
                var rid = -1L
                val rmap = HashMap<Long, Long>()
                val rreps =
                    unfinished.map { it ->
                        val m = opMain.getValue(it.id).let { v -> if (it.mainType.showsPercent) v * 100 * 5 else Math.floor(v * 5) }
                        val q = finishes(it, m, 1, kotlin.random.Random(it.id.hashCode() * 31 + round + 1)).first()
                        Item(rid--, q.slot, q.set, q.rank, q.level, 15, q.mainType, q.mainValue, q.subs, false, null).also { r ->
                            rmap[r.id] =
                                it.id
                        }
                    }
                val lines = java.util.concurrent.ConcurrentLinkedQueue<String>()
                spares.filter { "${it.key}|$round" !in done }.parallelStream().forEach { s ->
                    val req = s.job.req
                    val excl =
                        reserved -
                            c.box
                                .gearOf(s.job.h.id)
                                .values
                                .map { it.id }
                                .toSet()
                    val setup = Setup(s.job.h, content.heroes.getValue(s.job.h.code), s.job.cfg, req, c.box, content, { true }, excl)
                    val roll = if (s.roll.isNaN()) 0.01 * kotlin.math.abs(s.v0) else s.roll
                    val thr = s.v0 + roll
                    var pool = rreps.filter { q -> req.mains[q.slot].let { m -> m.isNullOrEmpty() || q.mainType in m } }
                    val builds = JSONArray()
                    for (k in 0 until 8) {
                        if (pool.isEmpty()) break
                        val r = SearchRun(setup, 1, extra = pool, startThreshold = thr)
                        r.run(1, limitMs)
                        val b = r.builds().firstOrNull()?.takeIf { it.value > thr } ?: break
                        val used = b.items.filter { it.id < 0 }
                        if (used.isEmpty()) break
                        builds.put(
                            JSONObject().put("gain", (b.value - s.v0) / roll).put("pieces", JSONArray(used.map { rmap.getValue(it.id) })),
                        )
                        pool = pool.filter { q -> used.none { it.id == q.id } }
                    }
                    lines +=
                        JSONObject()
                            .put(
                                "key",
                                "${s.key}|$round",
                            ).put("hero", s.job.h.name)
                            .put("round", round)
                            .put("builds", builds)
                            .toString()
                }
                out.appendText(lines.joinToString("\n", postfix = "\n"))
                log.appendText("round %d done\n".format(round))
            }
            log.appendText("groups finished\n")
            return
        }
        for (s in spares) {
            if (s.key in done) continue
            val t0 = System.nanoTime()
            val req = s.job.req
            val excl =
                reserved -
                    c.box
                        .gearOf(s.job.h.id)
                        .values
                        .map { it.id }
                        .toSet()
            val setup = Setup(s.job.h, content.heroes.getValue(s.job.h.code), s.job.cfg, req, c.box, content, { true }, excl)
            val roll = if (s.roll.isNaN()) 0.01 * kotlin.math.abs(s.v0) else s.roll
            val thr = s.v0 + roll
            val fit = reps.filter { q -> req.mains[q.slot].let { m -> m.isNullOrEmpty() || q.mainType in m } }
            var pool = fit
            val builds = JSONArray()
            repeat(8) {
                if (pool.isEmpty()) return@repeat
                val r = SearchRun(setup, 1, extra = pool, startThreshold = thr)
                r.run(threads, limitMs)
                val b = r.builds().firstOrNull()?.takeIf { it.value > thr } ?: return@repeat
                val used = b.items.filter { it.id < 0 }
                if (used.isEmpty()) return@repeat
                val alone =
                    if (used.size < 2) {
                        JSONArray()
                    } else {
                        JSONArray(
                            used.map { q ->
                                val r1 = SearchRun(setup, 1, extra = listOf(q), startThreshold = thr)
                                r1.run(threads, limitMs)
                                r1.builds().firstOrNull()?.takeIf { it.value > thr } != null
                            },
                        )
                    }
                builds.put(
                    JSONObject()
                        .put("gain", (b.value - s.v0) / roll)
                        .put("pieces", JSONArray(used.map { repOf.getValue(it.id) }))
                        .put("aloneBeats", alone)
                        .put("timedOut", r.engine.timedOut),
                )
                pool = pool.filter { q -> used.none { it.id == q.id } }
            }
            out.appendText(
                JSONObject()
                    .put("key", s.key)
                    .put("hero", s.job.h.name)
                    .put("offered", fit.size)
                    .put("builds", builds)
                    .toString() + "\n",
            )
            log.appendText("%s: %d builds, %.1f s\n".format(s.job.h.name, builds.length(), (System.nanoTime() - t0) / 1e9))
        }
        log.appendText("groups finished\n")
    }

    /**
     * E7_STOCK_EXPORT=1: data for a page that scores finishes in the browser. For each (your setup, slot, set) a piece in
     * E7_STOCK_PAIRS (JSON [{id, hero}]) could fill, the E7_STOCK_COMPLETIONS (40) best builds with an empty piece of that
     * set in that slot: their other five pieces and final stats. A finish's best build is then, nearly always, one of
     * these plus the finish. Checked here on E7_STOCK_SIMS finishes per pair against the full search with the finish fixed.
     * Writes E7_STOCK_OUT (JSON: heroes with targets, base, bar build; completions) and a check summary in the log.
     */
    @Test
    fun export() {
        val c = ctx()
        assumeTrue(c != null && System.getenv("E7_STOCK_EXPORT") == "1")
        c!!
        val out = File(System.getenv("E7_STOCK_OUT")!!)
        val log = File(out.path + ".log")
        val reserved = ids(System.getenv("E7_STOCK_EXCLUDE"))
        val m = System.getenv("E7_STOCK_COMPLETIONS")?.toInt() ?: 40
        val nSims = System.getenv("E7_STOCK_SIMS")?.toInt() ?: 30
        val spares = loadSpares(c, log).filter { it.own }.associateBy { it.key }
        val byId = c.box.itemsById
        val raw = JSONObject(File(System.getenv("E7_STOCK_BOX")!!).readText()).getJSONArray("items")
        val opMain =
            (0 until raw.length()).associate { i ->
                raw.getJSONObject(i).let { o ->
                    o.getLong("id") to
                        o.getJSONArray("op").getJSONArray(0).getDouble(1)
                }
            }
        val pairsJ = JSONArray(File(System.getenv("E7_STOCK_PAIRS")!!).readText())
        val pairs =
            (0 until pairsJ.length()).map { pairsJ.getJSONObject(it).let { o -> o.getLong("id") to o.getString("hero") } }.filter {
                it.second in
                    spares
            }

        class H(
            val s: Spare,
        ) {
            val req = s.job.req
            val excl =
                reserved -
                    c.box
                        .gearOf(s.job.h.id)
                        .values
                        .map { it.id }
                        .toSet()
            val setup = Setup(s.job.h, content.heroes.getValue(s.job.h.code), s.job.cfg, req, c.box, content, { true }, excl)
            val hb = setup.hb
            val obj = Objectives.of(req, hb)
            val maxs = setup.maxs()
            val slotSetups = java.util.concurrent.ConcurrentHashMap<Slot, Setup>()

            fun slotSetup(sl: Slot) =
                slotSetups.getOrPut(sl) {
                    Setup(
                        s.job.h,
                        content.heroes.getValue(s.job.h.code),
                        s.job.cfg,
                        req,
                        c.box,
                        content,
                        { true },
                        excl +
                            c.box.items
                                .filter { it.slot == sl }
                                .map { it.id },
                    )
                }

            fun ok(st: DoubleArray) =
                (0 until 8).all {
                    (req.mins[it].isNaN() || st[it] >= req.mins[it]) &&
                        (maxs[it].isNaN() || st[it] <= maxs[it])
                }
        }
        val heroes = pairs.map { it.second }.distinct().associateWith { H(spares.getValue(it)) }
        val combos =
            pairs
                .mapNotNull { (id, hk) ->
                    val it = byId.getValue(id)
                    val h = heroes.getValue(hk)
                    if (h.req.mains[it.slot].let { mm -> !mm.isNullOrEmpty() && it.mainType !in mm }) null else Triple(hk, it.slot, it.set)
                }.distinct()

        class Comp(
            val ids: List<Long>,
            val st: DoubleArray,
            val gs: Double,
        )
        val comps = java.util.concurrent.ConcurrentHashMap<Triple<String, Slot, String>, List<Comp>>()
        val t0 = System.nanoTime()
        combos.parallelStream().forEach { k ->
            val (hk, sl, set) = k
            val h = heroes.getValue(hk)
            val main =
                h.req.mains[sl]?.firstOrNull() ?: e7.gearscan.opt.Ghost
                    .mainsFor(sl)
                    .first()
            val dummy = Item(-1, sl, set, "Epic", 90, 15, main, 0.0, emptyList(), false, null)
            val r = SearchRun(h.slotSetup(sl), m, extra = listOf(dummy))
            r.run(1, limitMs)
            comps[k] = r.builds().map { b -> Comp(b.items.filter { it.id != -1L }.map { it.id }, b.final.stats.copyOf(8), b.final.gs) }
        }
        log.appendText("completions: %d combos, %.0f s\n".format(combos.size, (System.nanoTime() - t0) / 1e9))
        // check: the best completion plus the finish against the full search with the finish fixed
        val agree =
            java.util.concurrent.atomic
                .AtomicInteger()
        val total =
            java.util.concurrent.atomic
                .AtomicInteger()
        val decisionAgree =
            java.util.concurrent.atomic
                .AtomicInteger()
        val worst =
            java.util.concurrent.atomic
                .AtomicReference(0.0)
        pairs.parallelStream().forEach { (id, hk) ->
            val it = byId.getValue(id)
            val h = heroes.getValue(hk)
            val list = comps[Triple(hk, it.slot, it.set)] ?: return@forEach
            val mv = opMain.getValue(id).let { v -> if (it.mainType.showsPercent) v * 100 * 5 else Math.floor(v * 5) }
            val roll = if (h.s.roll.isNaN()) 0.01 * kotlin.math.abs(h.s.v0) else h.s.roll
            for (q in finishes(it, mv, nSims, kotlin.random.Random(it.id.hashCode()))) {
                val vq =
                    e7.gearscan.calc.Calc
                        .itemVec(q, h.hb, h.req.reforged)
                val sur =
                    list.maxOfOrNull { cp ->
                        val st = DoubleArray(8) { i -> cp.st[i] + vq[i] }
                        if (h.ok(st)) h.obj.eval(st + doubleArrayOf(cp.gs + q.score), 1.0, 1.0) else Double.NEGATIVE_INFINITY
                    } ?: Double.NEGATIVE_INFINITY
                val r = SearchRun(h.slotSetup(it.slot), 1, extra = listOf(q))
                r.run(1, limitMs)
                val ex = r.builds().firstOrNull()?.value ?: Double.NEGATIVE_INFINITY
                total.incrementAndGet()
                if (kotlin.math.abs(ex - sur) < 1e-6 || (ex == sur)) agree.incrementAndGet()
                if ((ex > h.s.v0 + roll) == (sur > h.s.v0 + roll) &&
                    (ex > h.s.v0 + 0.05 * roll) == (sur > h.s.v0 + 0.05 * roll)
                ) {
                    decisionAgree.incrementAndGet()
                }
                if (ex.isFinite() && sur.isFinite()) worst.accumulateAndGet((ex - sur) / roll) { a, b -> maxOf(a, b) }
            }
        }
        log.appendText(
            "check: %d finishes, same value %d, same verdict %d, worst shortfall %.2f rolls\n".format(
                total.get(),
                agree.get(),
                decisionAgree.get(),
                worst.get(),
            ),
        )
        val hj = JSONObject()
        for ((hk, h) in heroes) {
            val bar = h.s.best.map { byId.getValue(it) }
            val f =
                e7.gearscan.calc.Calc
                    .final(h.hb, bar, h.req.reforged, content)
            hj.put(
                hk,
                JSONObject()
                    .put("name", h.s.job.h.name)
                    .put("goal", h.req.goal.name)
                    .put("targets", JSONArray(h.req.targets.map { if (it.isNaN()) JSONObject.NULL else it }))
                    .put("weights", JSONArray(h.req.weights.toList()))
                    .put("mins", JSONArray(h.req.mins.map { if (it.isNaN()) JSONObject.NULL else it }))
                    .put("maxs", JSONArray(h.maxs.map { if (it.isNaN()) JSONObject.NULL else it }))
                    .put("rollSize", JSONArray(Objectives.rollSize(h.hb).toList()))
                    .put("base", JSONArray(listOf(h.hb.base.atk, h.hb.base.hp, h.hb.base.def)))
                    .put("mult", JSONArray(h.hb.mult.toList()))
                    .put("reforged", h.req.reforged)
                    .put(
                        "mains",
                        JSONObject(
                            h.req.mains.entries
                                .associate { (k, v) -> k.key to JSONArray(v.map { it.key }) },
                        ),
                    ).put("v0", h.s.v0)
                    .put("roll", if (h.s.roll.isNaN()) 0.01 * kotlin.math.abs(h.s.v0) else h.s.roll)
                    .put(
                        "bar",
                        JSONArray(h.s.best),
                    ).put("barStats", JSONArray(f.stats.take(8).map { Math.round(it * 10) / 10.0 }))
                    .put("barGs", f.gs),
            )
        }
        val cj = JSONObject()
        for ((k, list) in comps) {
            cj.put(
                "${k.first}|${k.second.key}|${k.third}",
                JSONArray(
                    list.map { cp ->
                        JSONArray().put(JSONArray(cp.ids)).put(JSONArray(cp.st.map { Math.round(it * 100) / 100.0 })).put(
                            Math.round(cp.gs * 100) / 100.0,
                        )
                    },
                ),
            )
        }
        out.writeText(JSONObject().put("heroes", hj).put("completions", cj).toString())
        log.appendText("export finished\n")
    }

    private fun loadSpares(
        c: Ctx,
        log: File,
    ): List<Spare> {
        val pop = System.getenv("E7_STOCK_POP")?.let { population(File(it), log) }?.associateBy { it.key } ?: emptyMap()
        val spares = ArrayList<Spare>()
        for (path in System.getenv("E7_STOCK_CHAINS")!!.split(",")) {
            for (raw in File(path).readLines()) {
                if (raw.isBlank()) continue
                val l = JSONObject(raw)
                val best = l.getJSONArray("best").let { a -> (0 until a.length()).map(a::getLong) }
                if (best.isEmpty()) continue
                val key = l.getString("key")
                val job =
                    pop[key] ?: l.optLong("hero").takeIf { it != 0L }?.let { id ->
                        val cfg = c.configs.getValue(id)
                        PopJob(key, c.heroOf(id)!!, cfg, cfg.request!!.copy(), JSONObject())
                    } ?: continue
                job.req.alone(false)
                spares += Spare(key, job, best, l.getDouble("v0"), l.getJSONObject("slots"), l.optDouble("roll", Double.NaN), l.has("hero"))
            }
        }
        return spares
    }

    private val roll85 =
        mapOf(
            GearStat.SPD to (2 to 4),
            GearStat.CR to (3 to 5),
            GearStat.CD to (4 to 7),
            GearStat.ATK_P to (4 to 8),
            GearStat.HP_P to (4 to 8),
            GearStat.DEF_P to (4 to 8),
            GearStat.EFF to (4 to 8),
            GearStat.RES to (4 to 8),
            GearStat.ATK to (33 to 46),
            GearStat.HP to (157 to 202),
            GearStat.DEF to (28 to 34),
        )
    private val roll88 =
        mapOf(
            GearStat.SPD to (3 to 4),
            GearStat.CR to (3 to 6),
            GearStat.CD to (4 to 8),
            GearStat.ATK_P to (5 to 9),
            GearStat.HP_P to (5 to 9),
            GearStat.DEF_P to (5 to 9),
            GearStat.EFF to (5 to 9),
            GearStat.RES to (5 to 9),
            GearStat.ATK to (37 to 53),
            GearStat.HP to (178 to 229),
            GearStat.DEF to (32 to 39),
        )

    private fun finishes(
        item: Item,
        main15: Double,
        n: Int,
        rnd: kotlin.random.Random,
    ): List<Item> {
        val roll = if (item.level >= 88) roll88 else roll85
        val pool =
            GearStat.entries.filter { t ->
                t != item.mainType &&
                    !(item.slot == Slot.WEAPON && t.stat == e7.gearscan.data.Stat.DEF) &&
                    !(item.slot == Slot.ARMOR && t.stat == e7.gearscan.data.Stat.ATK)
            }
        return List(n) {
            val types = item.subs.map { s -> s.type }.toMutableList()
            val vals = item.subs.map { s -> s.value }.toMutableList()
            val rolls = item.subs.map { s -> s.rolls }.toMutableList()
            var e = item.enhance + 3
            while (e <= 15) {
                if (item.rank == "Heroic" && e == 12 && types.size < 4) {
                    val t = pool.filter { p -> p !in types }.random(rnd)
                    val (lo, hi) = roll.getValue(t)
                    types += t
                    vals += rnd.nextInt(lo, hi + 1).toDouble()
                    rolls += 1
                } else {
                    val i = rnd.nextInt(types.size)
                    val (lo, hi) = roll.getValue(types[i])
                    vals[i] += rnd.nextInt(lo, hi + 1)
                    rolls[i] += 1
                }
                e += 3
            }
            Item(
                item.id,
                item.slot,
                item.set,
                item.rank,
                item.level,
                15,
                item.mainType,
                main15,
                types.indices.map { i ->
                    Substat(types[i], vals[i], rolls[i], false)
                },
                false,
                null,
            )
        }
    }
}
