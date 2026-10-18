package e7.gearscan

import e7.gearscan.calc.Calc
import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Match
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Stat
import e7.gearscan.opt.Build
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.Objectives
import e7.gearscan.opt.PoolEffort
import e7.gearscan.opt.PoolLosses
import e7.gearscan.opt.PoolPoints
import e7.gearscan.opt.Setup
import e7.gearscan.opt.pricedPool
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A phone's "Back up everything" file through the app's pool run: its tiers, targets, rules and kept heroes.
 * Needs E7_BACKUP; E7_LOSSES and E7_EFFORT pick the settings (default the backup's); E7_REPORT gets the table.
 */
class PoolBackupTest {
    private val content = TestContent.content
    private lateinit var box: Box
    private val configs = HashMap<Long, HeroConfig>()
    private var tiers = mapOf<Long, Int>()

    private fun tier(id: Long) = tiers[id] ?: 1
    private fun name(h: OwnedHero) = h.name.ifEmpty { content.heroes[h.code]?.name ?: h.code }

    private fun setup(id: Long, excluded: Set<Long> = emptySet(), canTakeFrom: (Long) -> Boolean = { tier(it) < tier(id) }): Setup {
        val h = box.hero(id)!!
        return Setup(h, content.heroes.getValue(h.code), configs.getValue(id), configs.getValue(id).request!!, box, content, canTakeFrom, excluded)
    }

    private fun current(id: Long) = setup(id).let { s -> s.build(s.current().values.toList()) }

    /** The match % the app shows (rounded, 0..100), so pool scores compare with the app's. */
    private fun match(id: Long, b: Build): Int {
        val s = setup(id); val req = s.req
        val f = Calc.final(s.hb, Ghost.mainsOnly(req), req.reforged, content)
        val roll = Objectives.rollSize(s.hb)
        val missing = Match.missingSets(b.items.groupingBy { it.set }.eachCount(), req.setAlts) { content.set(it) }
        return Match.of({ b.final[it] }, { f[it] }, req.targets, req.weights, "", { roll[it.ordinal] }, missing)?.pct ?: 0
    }

    private fun worn(id: Long) = box.gearOf(id).values.map { it.id }.toSet()

    @Test
    fun backupRun() {
        val path = System.getenv("E7_BACKUP")
        assumeTrue(path != null)
        val backup = JSONObject(File(path!!).readText())
        val st = backup.getJSONObject("state")
        box = Box(backup.getJSONObject("box"))
        st.getJSONObject("configs").let { c -> c.keys().forEach { k -> configs[k.toLong()] = HeroConfig.fromJson(c.getJSONObject(k)) } }
        tiers = st.getJSONObject("tiers").let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }
        val poolTiers = st.getJSONArray("poolTiers").let { a -> (0 until a.length()).map(a::getInt).toSet() }
        val extra = st.optJSONArray("poolExtra")?.let { a -> (0 until a.length()).map(a::getLong).toSet() } ?: emptySet()
        // backups from before the price run kept their Keep heroes as a role
        val keep = (st.optJSONArray("poolSkip")?.let { a -> (0 until a.length()).map(a::getLong) } ?: st.optJSONObject("poolRoles")
            ?.let { o -> o.keys().asSequence().filter { o.getString(it) == "KEEP" }.map { it.toLong() }.toList() } ?: emptyList()).toSet()
        val losses = PoolLosses.valueOf(System.getenv("E7_LOSSES") ?: st.optString("poolLosses").ifEmpty { "SAFE" })
        val effort = PoolEffort.valueOf(System.getenv("E7_EFFORT") ?: st.optString("poolEffort").ifEmpty { "BALANCED" })

        val members = box.heroes.filter { (tier(it.id) in poolTiers || it.id in extra) && content.heroes[it.code] != null && configs[it.id]?.request?.targets?.any { t -> !t.isNaN() } == true }
        val pts = members.associate { it.id to PoolPoints(setup(it.id), current(it.id)) }
        val now = members.associate { it.id to pts.getValue(it.id).of(current(it.id)) }
        val ids = members.filter { it.id !in keep }
            .sortedWith(compareByDescending<OwnedHero> { tier(it.id) }.thenBy { now[it.id] ?: 101.0 }.thenBy { name(it) }).map { it.id }
        val inRun = ids.toSet()
        val searches = java.util.Collections.synchronizedList(ArrayList<Triple<String, Double, Boolean>>())
        val t0 = System.nanoTime()
        val pr = pricedPool(ids, ::tier, keep.flatMap(::worn).toSet(), { id, excluded -> setup(id, excluded) { h -> h in inRun || tier(h) < tier(id) } },
            { s, r ->
                val t = System.nanoTime(); r.run(6, if (s.req.mods) 2 * POOL_HERO_MS else POOL_HERO_MS)
                searches += Triple(name(s.hero), (System.nanoTime() - t) / 1e9, r.engine.timedOut)
            }, losses, effort,
            rounds = (System.getenv("E7_ROUNDS") ?: "4").toInt(), tries = (System.getenv("E7_TRIES") ?: "3").toInt())
        var result = pr.run()
        // E7_FORCE: give the first trade not made to its taker and run again, as "Moves" + re-run does in the app
        System.getenv("E7_FORCE")?.let {
            val d = pr.refusals(result).first()
            val pr2 = pricedPool(ids, ::tier, keep.flatMap(::worn).toSet(), { id, excluded -> setup(id, excluded) { h -> h in inRun || tier(h) < tier(id) } },
                { s, r -> r.run(6, if (s.req.mods) 2 * POOL_HERO_MS else POOL_HERO_MS) }, losses, effort, give = mapOf(d.item to d.taker))
            result = pr2.run()
            val moved = result.getValue(d.taker).items.any { it.id == d.item }
            println("forced ${name(box.hero(d.holder)!!)} -> ${name(box.hero(d.taker)!!)}: moved=$moved, unmoved=${pr2.unmoved}")
            System.getenv("E7_REPORT")?.let { f -> File("$f.force").writeText("forced ${name(box.hero(d.holder)!!)} -> ${name(box.hero(d.taker)!!)}: moved=$moved\n") }
            assertTrue("a forced trade neither moved nor was reported", moved || d.item in pr2.unmoved)
        }
        val secs = (System.nanoTime() - t0) / 1e9

        val after = ids.associateWith { pts.getValue(it).of(result.getValue(it)) }
        fun moves(id: Long) = result.getValue(id).items.count { !it.ghost && it.equippedOn != id }
        fun mods(id: Long) = result.getValue(id).items.count { it.mod != null }
        // the app's pool score: tier-weighted match %, kept heroes included
        val all = members.map { it.id }
        val matchNow = all.associateWith { match(it, current(it)) }
        val matchAfter = all.associateWith { id -> result[id]?.let { match(id, it) } ?: matchNow.getValue(id) }
        fun score(m: Map<Long, Int>) = m.entries.sumOf { (id, v) -> tier(id) * v.toDouble() } / m.keys.sumOf { tier(it).toDouble() } * 100
        val out = StringBuilder()
        out.appendLine("rounds ${System.getenv("E7_ROUNDS") ?: "4"}, tries ${System.getenv("E7_TRIES") ?: "3"}")
        out.appendLine("%s / %s: %d heroes (%d kept), %.1f s, %d proposals. Pool score %.0f -> %.0f, %d moves, %d mods".format(losses.label, effort.label,
            ids.size, keep.size, secs, pr.proposals, score(matchNow), score(matchAfter), ids.sumOf(::moves), ids.sumOf(::mods)))
        out.appendLine("%-26s %-2s %5s %5s %6s %4s".format("hero", "t", "now", "after", "spd", "mv"))
        for (id in ids) {
            val b = result.getValue(id); val cur = current(id)
            out.appendLine("%-26s %-2d %4d%% %4d%% %+6.0f %4d".format(name(box.hero(id)!!).take(26), tier(id), matchNow.getValue(id), matchAfter.getValue(id),
                b.final[Stat.SPD] - cur.final[Stat.SPD], moves(id)))
        }
        pr.refusals(result).groupBy { it.holder to it.taker }.forEach { (k, ds) -> out.appendLine("not made: ${name(box.hero(k.first)!!)} kept ${ds.size} for ${name(box.hero(k.second)!!)}") }
        val worse = ids.filter { after.getValue(it) < now.getValue(it) - 1e-6 }
        out.appendLine("worse than now: ${worse.map { name(box.hero(it)!!) }}")
        pr.spent.forEach { (k, v) -> out.appendLine("  %-18s %4d searches %6.1f s".format(k, v.first, v.second)) }
        val slow = searches.groupBy { it.first }.mapValues { (_, l) -> Triple(l.size, l.sumOf { it.second }, l.count { it.third }) }
            .entries.sortedByDescending { it.value.second }
        out.appendLine("searches ${searches.size}, %.1f s, %d timed out; slowest heroes:".format(searches.sumOf { it.second }, searches.count { it.third }))
        slow.take(8).forEach { (n, v) -> out.appendLine("  %-26s %3d searches %6.1f s %d timed out".format(n.take(26), v.first, v.second, v.third)) }
        println(out)
        System.getenv("E7_REPORT")?.let { File(it).writeText(out.toString()) }

        if (losses == PoolLosses.SAFE) assertTrue("worse with Never worse: $worse", worse.isEmpty())
        val used = result.values.flatMap { b -> b.items.filter { !it.ghost }.map { it.id } }
        assertTrue("a piece on two heroes", used.size == used.toSet().size)
        val kept = keep.flatMap(::worn).toSet()
        assertTrue("a kept hero's piece moved", result.values.none { b -> b.items.any { it.id in kept } })
    }
}
