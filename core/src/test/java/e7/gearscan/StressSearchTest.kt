package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Request
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The exact answers for SearchSpeedTest's stress cases (same heroes and variants), to judge what a device that stops
 * at its time limit shows. Needs E7_BACKUP; E7_CASES is a regex over case labels, E7_CAP_MS the time cap (default 10 min).
 */
class StressSearchTest {
    @Test
    fun stress() {
        val path = System.getenv("E7_BACKUP")
        assumeTrue(path != null)
        val content = TestContent.content
        val backup = JSONObject(File(path!!).readText())
        val box = Box(backup.getJSONObject("box"))
        val configs = backup.getJSONObject("state").getJSONObject("configs")
        val filter = Regex(System.getenv("E7_CASES") ?: ".*")
        val cap = System.getenv("E7_CAP_MS")?.toLong() ?: 600_000L
        val picks = listOf("Frieren", "Rhianna and Luciella", "Commander Pavel", "Lionheart Cermia", "Briar Witch Iseria", "Aube")
        for (name in picks) {
            val h = box.heroes.first { (it.name.ifEmpty { content.heroes[it.code]?.name }) == name }
            val cfg = HeroConfig.fromJson(configs.getJSONObject(h.id.toString()))
            val base = cfg.request!!

            fun all(edit: Request.() -> Unit): Setup {
                val q = base.copy().apply { lowerPriorityOnly = false }.apply(edit)
                return Setup(h, content.heroes.getValue(h.code), cfg, q, box, content, { true })
            }
            val cases =
                listOf(
                    "$name · all gear" to all {},
                    "$name · all gear, no sets" to all { setAlts = emptyList() },
                    "$name · dmg no sets mods spd200 cr80" to
                        all {
                            goal = Goal.DAMAGE
                            setAlts = emptyList()
                            mods = true
                            mins.fill(Double.NaN)
                            mins[3] = 200.0
                            mins[4] = 80.0
                        },
                    "$name · ehp no sets mods spd200" to
                        all {
                            goal = Goal.EHP
                            setAlts = emptyList()
                            mods = true
                            mins.fill(Double.NaN)
                            mins[3] = 200.0
                        },
                    "$name · speed no sets mods" to
                        all {
                            goal = Goal.SPEED
                            setAlts = emptyList()
                            mods = true
                        },
                )
            for ((label, s) in cases) {
                if (!filter.containsMatchIn(label)) continue
                val r = SearchRun(s, 50)
                val t0 = System.nanoTime()
                r.run(6, cap)
                val b = r.builds()
                println(
                    "%-48s %7d ms %s top1=%.6f top50=%.6f".format(
                        label,
                        (System.nanoTime() - t0) / 1_000_000,
                        if (r.engine.timedOut) "TIMEOUT" else "exact",
                        b.firstOrNull()?.value ?: Double.NaN,
                        b.lastOrNull()?.value ?: Double.NaN,
                    ),
                )
            }
        }
    }
}
