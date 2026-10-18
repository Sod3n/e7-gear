package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Request
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The app's real search path on a saved request. Needs E7_GEAR and E7_REQUEST (a Request JSON). */
class AppSearchTest {
    @Test
    fun appRequest() {
        val gear = System.getenv("E7_GEAR"); val reqPath = System.getenv("E7_REQUEST")
        assumeTrue(gear != null && reqPath != null)
        val content = TestContent.content
        val box = Box.parse(File(gear!!).readText())
        val h = box.heroes.first { it.name == (System.getenv("E7_HERO") ?: "Frieren") }
        val req = Request.fromJson(JSONObject(File(reqPath!!).readText()))
        System.getenv("E7_MODS_PER_ITEM")?.let { e7.gearscan.opt.MODS_PER_ITEM = it.toInt() }
        val setup = Setup(h, content.heroes.getValue(h.code), HeroConfig(), req, box, content, { true })
        for (k in listOf(1, 50)) {
            val r = SearchRun(setup, k)
            val t = System.nanoTime()
            r.run(4, 60_000)
            val b = r.builds()
            println("k=%-3d %.0f ms nodes=%,d timeout=%b kept=%s best=%.4f spd=%.0f".format(k, (System.nanoTime() - t) / 1e6,
                r.engine.nodes.get(), r.engine.timedOut, r.engine.keptPerSlot.toList(), b.first().value, b.first().final.stats[3]))
            if (req.maxChanges < 6) org.junit.Assert.assertTrue("change cap broken", b.all { bb -> bb.items.count { !it.ghost && (it.id != setup.current()[it.slot]?.id || it.mod != null) } <= req.maxChanges })
            if (req.capCrit && req.maxs[4].isNaN()) org.junit.Assert.assertTrue("crit cap broken", b.all { it.final.stats[4] <= e7.gearscan.opt.CRIT_CAP + 1e-3 })
            b.first().items.filter { it.mod != null }.forEach { println("  mod: ${it.slot} ${it.mod!!.from} ${it.mod!!.fromValue} -> ${it.mod!!.to} ${it.mod!!.toValue}") }
            if (k == 50) {
                val t2 = System.nanoTime()
                val ghosts = Ghost.candidates(setup, b.first(), req.reforged)
                val g = SearchRun(setup, 1, ghosts, maxGhost = 1, startThreshold = b.first().value)
                g.run(4, 60_000)
                val gb = g.builds().firstOrNull()
                println("ghost: %d candidates, %.0f ms, found=%s value=%.4f".format(ghosts.size, (System.nanoTime() - t2) / 1e6,
                    gb?.ghost?.let { "${it.slot} ${it.set} ${it.mainType}" }, gb?.value ?: Double.NaN))
            }
        }
    }
}
