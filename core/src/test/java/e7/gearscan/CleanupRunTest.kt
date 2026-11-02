package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.opt.Cleanup
import e7.gearscan.opt.FinishCheck
import e7.gearscan.opt.Setup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The cleanup list on a phone backup (E7_BACKUP; E7_BOX = a gear.txt to use instead of its box): duplicates, the pieces
 * below +12 no kind keeps for any stat pair (E7_KEEP, 2), and which of those a simulated finish still makes useful to a
 * hero with a setup (E7_SIMS 30, E7_ROUNDS 1). Prints counts and time; E7_OUT gets the ids as JSON.
 */
class CleanupRunTest {
    @Test
    fun cleanup() {
        val path = System.getenv("E7_BACKUP")
        assumeTrue(path != null)
        val backup = JSONObject(File(path!!).readText())
        val st = backup.getJSONObject("state")
        val box = System.getenv("E7_BOX")?.let { Box.parse(File(it).readText()) } ?: Box(backup.getJSONObject("box"))
        val content = TestContent.content
        val tiers = st.getJSONObject("tiers").let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }
        val poolTiers = st.getJSONArray("poolTiers").let { a -> (0 until a.length()).map(a::getInt).toSet() }
        val configs =
            st.getJSONObject("configs").let { c ->
                c.keys().asSequence().associate {
                    it.toLong() to
                        HeroConfig.fromJson(c.getJSONObject(it))
                }
            }
        val pool = box.heroes.filter { (tiers[it.id] ?: 1) in poolTiers && content.heroes[it.code] != null }
        val inUse =
            box.items
                .filter { it.equippedOn != null && pool.any { h -> h.id == it.equippedOn } }
                .map { it.id }
                .toSet()
        val t0 = System.nanoTime()
        val dups = Cleanup.duplicates(box, inUse)
        val stock = Cleanup.stock(box, inUse, System.getenv("E7_KEEP")?.toInt() ?: 2)
        val setups =
            box.heroes.filter { content.heroes[it.code] != null }.mapNotNull { h ->
                val cfg = configs[h.id] ?: return@mapNotNull null
                val req = cfg.request?.copy() ?: return@mapNotNull null
                if (req.goal == Goal.TARGETS && req.targets.all { it.isNaN() }) return@mapNotNull null
                req.lowerPriorityOnly = false
                req.keepCurrent = false
                req.maxChanges = 6
                req.mods = false
                val own =
                    box
                        .gearOf(h.id)
                        .values
                        .map { it.id }
                        .toSet()
                Setup(h, content.heroes.getValue(h.code), cfg, req, box, content, { true }, inUse - own)
            }
        val tickets = box.items.filter { Cleanup.ticket(it) && it.id !in inUse }
        val t1 = System.nanoTime()
        val r =
            FinishCheck(setups, System.getenv("E7_SIMS")?.toInt() ?: 30)
                .check(stock.map { it.item }, partners = tickets, rounds = System.getenv("E7_ROUNDS")?.toInt() ?: 1)
        val listed = stock.filter { it.item.id !in r.kept }
        println(
            "cleanup: ${pool.size} pool heroes, ${setups.size} setups, ${inUse.size} pieces in use; duplicates ${dups.size}; " +
                "stock candidates ${stock.size}, kept by the finish check ${r.kept.size} (unknown ${r.unknown}); listed ${listed.size}; " +
                "rules %.1f s, finish check %.1f s".format((t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9),
        )
        System.getenv("E7_OUT")?.let { out ->
            File(out).writeText(
                JSONObject()
                    .put("duplicates", JSONArray(dups.map { it.item.id }))
                    .put("candidates", JSONArray(stock.map { it.item.id }))
                    .put("kept", JSONArray(r.kept.toList()))
                    .put("listed", JSONArray(listed.map { it.item.id }))
                    .toString(),
            )
        }
    }
}
