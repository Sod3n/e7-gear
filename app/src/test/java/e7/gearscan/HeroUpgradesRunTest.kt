package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.HeroUpgrades
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Pieces that could improve heroes with a setup, on a phone backup (E7_BACKUP; E7_THREADS, E7_SIMS). Prints time and
 * per-hero counts, then re-checks E7_SPOT (40) random piece/finish/hero triples with a search that has only that finish
 * in the piece's slot: the fast answer must agree.
 */
class HeroUpgradesRunTest {
    @Test
    fun heroUpgrades() {
        val path = System.getenv("E7_BACKUP"); assumeTrue(path != null)
        val backup = JSONObject(File(path!!).readText())
        val st = backup.getJSONObject("state")
        val box = Box(backup.getJSONObject("box"))
        val content = TestContent.content
        val tiers = st.getJSONObject("tiers").let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }
        val poolTiers = st.getJSONArray("poolTiers").let { a -> (0 until a.length()).map(a::getInt).toSet() }
        val configs = st.getJSONObject("configs").let { c -> c.keys().asSequence().associate { it.toLong() to HeroConfig.fromJson(c.getJSONObject(it)) } }
        val pool = box.heroes.filter { (tiers[it.id] ?: 1) in poolTiers && content.heroes[it.code] != null }
        val inUse = box.items.filter { it.equippedOn != null && pool.any { h -> h.id == it.equippedOn } }.map { it.id }.toSet()
        val setups = box.heroes.filter { content.heroes[it.code] != null }.mapNotNull { h ->
            val cfg = configs[h.id] ?: return@mapNotNull null
            val req = cfg.request?.copy() ?: return@mapNotNull null
            if (req.goal == Goal.TARGETS && req.targets.all { it.isNaN() }) return@mapNotNull null
            req.lowerPriorityOnly = false; req.keepCurrent = false; req.maxChanges = 6; req.mods = false
            val own = box.gearOf(h.id).values.map { it.id }.toSet()
            Setup(h, content.heroes.getValue(h.code), cfg, req, box, content, { true }, inUse - own)
        }.sortedByDescending { tiers[it.hero.id] ?: 1 }
        val pieces = box.items.filter { it.enhance < 15 && UpgradeRecord.supported(it) }.sortedBy { it.id }
        val sims = System.getenv("E7_SIMS")?.toInt() ?: HeroUpgrades.SIMS
        val threads = System.getenv("E7_THREADS")?.toInt() ?: Runtime.getRuntime().availableProcessors()
        val t0 = System.nanoTime()
        val progress = HeroUpgrades.Progress()
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val slotMs = System.getenv("E7_SLOT_MS")?.toLong() ?: 4000
        val r = HeroUpgrades(sims, baseMs = maxOf(4000, slotMs), slotMs = slotMs, log = { log += it }).run(setups, pieces, threads = threads, resume = progress)
        val secs = (System.nanoTime() - t0) / 1e9
        val unknownPairs = r.picks.count { it.unknown > 0 }
        println("hero upgrades: ${setups.size} heroes, ${pieces.size} pieces x $sims finishes, %.1f s on $threads threads; ".format(secs) +
            "${r.picks.count { it.values.isNotEmpty() }} piece/hero pairs with a better finish, $unknownPairs with unknowns, ${r.unknownSteps} unsettled steps")
        val ms = Regex("finish (\\d+) ms")
        log.sortedByDescending { ms.find(it)!!.groupValues[1].toLong() }.take(25).forEach { println("  $it") }
        println("  steps: ${log.size}, total search ms ${log.sumOf { ms.find(it)!!.groupValues[1].toLong() }}")
        for (s in setups) {
            val b = r.bases.getValue(s.hero.id)
            val mine = r.picks.filter { it.hero == s.hero.id && it.values.isNotEmpty() }
                .sortedByDescending { p -> p.values.sumOf { it - (b.value ?: 0.0) } / sims }
            val top = mine.take(3).joinToString("; ") { p -> "${p.piece} ${p.values.size}/$sims +%.2f".format((p.values[p.values.size / 2] - (b.value ?: 0.0)) / b.unit) }
            println("  t${tiers[s.hero.id] ?: 1} %-24s %-8s base %s%s · %d pieces · %s".format(s.game.name ?: s.hero.code, s.req.goal.name,
                b.value?.let { "%.2f".format(it) } ?: "none", if (b.timedOut) " (timed out)" else "", mine.size, top))
        }

        // one tracked piece: first check (every hero's build without it) and a recheck after a recorded step (cached)
        for (id in listOfNotNull(System.getenv("E7_PIECE")?.toLong(), r.picks.maxByOrNull { it.values.size }?.piece)) {
            val p = pieces.firstOrNull { it.id == id } ?: continue
            val without = java.util.concurrent.ConcurrentHashMap<Long, HeroUpgrades.Base>()
            for (pass in listOf("first", "recheck")) {
                val t = System.nanoTime()
                val pr = HeroUpgrades(120).piece(setups, p, without, threads = threads)
                val union = pr.flatMap { it.hits.keys }.toSet().size
                println("piece $id +${p.enhance} $pass: ${pr.size} heroes, %.1f s, upgrade in $union/120 finishes; ".format((System.nanoTime() - t) / 1e9) +
                    pr.filter { it.hits.isNotEmpty() || it.now }.joinToString("; ") { h ->
                        "${setups.first { it.hero.id == h.hero }.game.name} ${h.hits.size}/120${if (h.now) " now" else ""} min=" +
                            h.minimal.joinToString(" | ") { q -> q.subs.joinToString(" ") { "${it.type.short}${it.type.format(it.value)}" } } })
            }
        }

        // spot check: a single-finish search must agree with the fast answer
        val rnd = Random(11)
        val all = HeroUpgrades.finishes(pieces, sims)
        val spots = System.getenv("E7_SPOT")?.toInt() ?: 40
        var agreed = 0; var hits = 0; var skipped = 0
        repeat(spots) {
            val s = setups[rnd.nextInt(setups.size)]
            val b = r.bases.getValue(s.hero.id)
            // half the spots from this hero's listed pieces, so hits get checked too
            val listed = r.picks.filter { it.hero == s.hero.id && it.values.isNotEmpty() }
            val want = if (it % 2 == 0 && listed.isNotEmpty()) listed[rnd.nextInt(listed.size)].piece else null
            val pi = if (want != null) pieces.indexOfFirst { p -> p.id == want } else rnd.nextInt(pieces.size)
            val si = pi * sims + rnd.nextInt(sims)
            val q = all[si]
            val piece = pieces[pi]
            if (b.timedOut || piece.id in s.excluded || (!s.req.allowLocked && piece.locked && piece.equippedOn != s.hero.id) ||
                s.req.mains[piece.slot].let { m -> !m.isNullOrEmpty() && piece.mainType !in m }) { skipped++; return@repeat }
            val forced = Setup(s.hero, s.game, s.cfg, s.req, s.box, s.content, s.canTakeFrom, s.excluded + box.items.filter { it.slot == q.slot }.map { it.id })
            val run = SearchRun(forced, 1, extra = listOf(q), startThreshold = b.bar).also { it.run(1, 30_000) }
            val alone = run.builds().firstOrNull()?.value
            val step = progress.steps.getValue("${s.hero.id}:${q.slot.name}")
            val fast = step.hits[si]
            if (alone != null) hits++
            if (run.engine.timedOut || si in step.unknown) skipped++
            else if ((alone == null && fast == null) || (alone != null && fast != null && kotlin.math.abs(alone - fast) < 1e-6)) agreed++
            else println("  DISAGREE hero ${s.game.name} piece ${piece.id} finish $si: alone=$alone fast=$fast bar=${b.bar}")
        }
        println("spot check: $agreed agree, $skipped skipped, of $spots ($hits were hits alone)")
        assertEquals(spots - skipped, agreed)
    }
}
