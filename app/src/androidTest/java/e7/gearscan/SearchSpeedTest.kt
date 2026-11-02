package e7.gearscan

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.Goal
import e7.gearscan.data.Request
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-hero searches timed on whatever device runs them, from the "Back up everything" file in the test assets
 * (backup.json, from E7_FIXTURES). Argument `cases`: `saved` = every hero's own setup as the app runs it, `ghost` = the one-new-piece search after it,
 * `stress` = harder variants (all gear, no sets, damage/EHP/speed goals, impossible minimums); `match` = label parts (a,b; _ for a space),
 * `capMs` = time cap (default 60 s). Results in logcat under E7Speed, with what the app would show at its 15 s limit.
 */
class SearchSpeedTest {

    @Test
    fun searches() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val backup = runCatching { inst.context.assets.open("backup.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("needs backup.json in E7_FIXTURES", backup != null)
        // no compose rule: its host activity exists only in debug builds, and timings need a release build
        lateinit var graph: AppGraph
        inst.runOnMainSync { graph = startedGraph(inst.targetContext) }
        fun waitFor(ms: Long, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (!ok()) { assertTrue("timed out waiting for the model", System.currentTimeMillis() < end); Thread.sleep(100) }
        }
        waitFor(20_000) { graph.ready }
        waitFor(60_000) { graph.content.content.heroes.isNotEmpty() }
        var restored = false
        inst.runOnMainSync { restored = graph.scan.restore(backup!!) }
        assertTrue("backup didn't restore", restored)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        val box = graph.scan.box!!
        val args = InstrumentationRegistry.getArguments()
        val which = args.getString("cases") ?: "saved"
        // comma-separated label parts, _ for a space: am instrument's shell would split spaces and pipes
        val only = args.getString("match")?.split(",")?.map { it.replace('_', ' ') }
        val capMs = args.getString("capMs")?.toLong() ?: 60_000L
        Log.i("E7Speed", "threads=$threads cases=$which items=${box.items.size}")

        fun time(label: String, s: Setup, k: Int = 50) {
            if (only != null && only.none { it in label }) return
            val r = SearchRun(s, k)
            val t0 = System.nanoTime()
            // what the app would show had it stopped at its limit
            var atLimit: List<Double>? = null
            val sampler = Thread {
                try { Thread.sleep(graph.optimize.limitMs); atLimit = r.builds().map { it.value } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            r.run(threads, capMs)
            sampler.interrupt()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val b = r.builds()
            Log.i("E7Speed", "%-48s %6d ms %s builds=%d nodes=%,d top1=%.6f top50=%.6f".format(java.util.Locale.US, label, ms,
                if (r.engine.timedOut) "TIMEOUT" else if (ms > graph.optimize.limitMs) "OVER15" else "ok", b.size, r.engine.nodes.get(),
                b.firstOrNull()?.value ?: Double.NaN, b.lastOrNull()?.value ?: Double.NaN) +
                (atLimit?.let { " at15 top1=%.6f top50=%.6f".format(java.util.Locale.US, it.firstOrNull() ?: Double.NaN, it.lastOrNull() ?: Double.NaN) } ?: ""))
        }

        fun anyTimes(label: String, s: Setup) {
            if (only != null && only.none { it in label }) return
            val t0 = System.nanoTime()
            val a = SearchRun(s, 1).anyBuild(threads, 3_000)
            Log.i("E7Speed", "%-48s %6d ms any=%s".format(java.util.Locale.US, label, (System.nanoTime() - t0) / 1_000_000, a))
        }

        val saved = box.heroes.filter { h ->
            val q = graph.heroes.config(h.id).request ?: return@filter false
            q.goal != Goal.TARGETS || q.targets.any { !it.isNaN() } || q.mins.any { !it.isNaN() }
        }.sortedByDescending { graph.heroes.tier(it.id) }

        if (which == "saved") for (h in saved) time("T${graph.heroes.tier(h.id)} ${graph.heroes.heroName(h)}", graph.heroes.setup(h.id)!!)

        // the one-new-piece search that follows every search with a build, as the app runs it (its own 10 s cap)
        if (which == "ghost") for (h in saved) {
            val s = graph.heroes.setup(h.id)!!
            val best = SearchRun(s, 1).also { it.run(threads, capMs) }.builds().firstOrNull() ?: continue
            val t0 = System.nanoTime()
            val g = SearchRun(s, 1, Ghost.candidates(s, best, s.req.reforged, best.value), maxGhost = 1, startThreshold = best.value)
            g.run(threads, 10_000)
            Log.i("E7Speed", "%-48s %6d ms %s found=%b".format(java.util.Locale.US, "ghost T${graph.heroes.tier(h.id)} ${graph.heroes.heroName(h)}",
                (System.nanoTime() - t0) / 1_000_000, if (g.engine.timedOut) "TIMEOUT" else "ok", g.builds().firstOrNull()?.ghost != null))
        }

        if (which == "stress") {
            val picks = listOf("Frieren", "Rhianna and Luciella", "Commander Pavel", "Lionheart Cermia", "Briar Witch Iseria", "Aube")
            for (name in picks) {
                val h = saved.firstOrNull { graph.heroes.heroName(it) == name } ?: continue
                val base = graph.heroes.request(h.id)
                fun all(edit: Request.() -> Unit): Setup {
                    val q = base.copy().apply { lowerPriorityOnly = false }.apply(edit)
                    return Setup(h, graph.heroes.gameHero(h)!!, graph.heroes.config(h.id), q, box, graph.content.content, { true })
                }
                time("$name · all gear", all {})
                time("$name · all gear, no sets", all { setAlts = emptyList() })
                time("$name · dmg no sets mods spd200 cr80", all {
                    goal = Goal.DAMAGE; setAlts = emptyList(); mods = true; mins.fill(Double.NaN); mins[3] = 200.0; mins[4] = 80.0 })
                time("$name · ehp no sets mods spd200", all {
                    goal = Goal.EHP; setAlts = emptyList(); mods = true; mins.fill(Double.NaN); mins[3] = 200.0 })
                time("$name · speed no sets mods", all { goal = Goal.SPEED; setAlts = emptyList(); mods = true })
                val none = all { mins[3] = 400.0 }
                time("$name · impossible spd400", none)
                anyTimes("$name · impossible, rescue: no spd min", all { mins[3] = Double.NaN })
                anyTimes("$name · impossible, rescue: no sets", all { mins[3] = 400.0; setAlts = emptyList() })
            }
        }
        Log.i("E7Speed", "done")
    }
}
