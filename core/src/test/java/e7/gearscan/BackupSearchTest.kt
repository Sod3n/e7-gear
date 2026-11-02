package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.HeroConfig
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * One hero's search from a phone's "Back up everything" file, then again with each rule loosened alone.
 * Needs E7_BACKUP and E7_CODE; E7_MINS, E7_TARGETS, E7_WEIGHTS, E7_ARTIFACT, E7_MODS (1/0) override; E7_ANY=1 only checks feasibility.
 */
class BackupSearchTest {
    @Test
    fun hero() {
        val path = System.getenv("E7_BACKUP"); assumeTrue(path != null)
        val content = TestContent.content
        val backup = JSONObject(File(path!!).readText())
        val box = Box(backup.getJSONObject("box"))
        val h = box.heroes.first { it.code == System.getenv("E7_CODE") }
        val cfg = HeroConfig.fromJson(backup.getJSONObject("state").getJSONObject("configs").getJSONObject(h.id.toString()))
        val req = cfg.request!!
        req.lowerPriorityOnly = false
        System.getenv("E7_ARTIFACT")?.let { cfg.artifact = it }
        System.getenv("E7_MINS")?.split(",")?.forEachIndexed { i, v -> req.mins[i] = if (v.isEmpty()) Double.NaN else v.toDouble() }
        System.getenv("E7_WEIGHTS")?.split(",")?.forEachIndexed { i, v -> req.weights[i] = v.toInt() }
        System.getenv("E7_SETS")?.let { v -> req.setAlts = v.split("|").map { alt -> alt.split(",").associate { it.substringBefore(":") to it.substringAfter(":").toInt() } } }
        System.getenv("E7_MODS")?.let { req.mods = it == "1" }
        System.getenv("E7_TARGETS")?.split(",")?.forEachIndexed { i, v -> if (v.isNotEmpty()) req.targets[i] = v.toDouble() }
        println("mins=${req.mins.toList()} targets=${req.targets.toList()} sets=${req.setAlts} mains=${req.mains} minEnh=${req.minEnhance} capCrit=${req.capCrit}")
        fun run(label: String) {
            val setup = Setup(h, content.heroes.getValue(h.code), cfg, req, box, content, { true })
            val t0 = System.nanoTime()
            val r = SearchRun(setup, System.getenv("E7_K")?.toInt() ?: 5)
            Thread {
                var last = Double.NaN
                while (true) {
                    Thread.sleep(250)
                    val f = r.builds().firstOrNull() ?: continue
                    if (f.value != last) { last = f.value
                        println("  %6d ms best %s".format((System.nanoTime() - t0) / 1_000_000, f.final.stats.take(8).joinToString(" ") { "%.0f".format(it) })) }
                }
            }.apply { isDaemon = true; start() }
            if (System.getenv("E7_ANY") == "1") { val a = r.anyBuild(System.getenv("E7_THREADS")?.toInt() ?: 6, 60_000); println("any=$a") }
            else r.run(System.getenv("E7_THREADS")?.toInt() ?: 6, 60_000)
            val b = r.builds()
            System.getenv("E7_DUMP")?.let { f -> File(f).appendText("$label\n" + b.joinToString("\n") { bb -> "%.6f %s".format(bb.value, bb.items.map { it.id to it.mod?.to }.sortedBy { it.first }) } + "\n") }
            b.take(3).forEach { bb -> println("    sets=" + bb.items.groupingBy { it.set }.eachCount() + " " + bb.final.stats.take(8).joinToString(" ") { "%.0f".format(it) } +
                " mods=" + bb.items.mapNotNull { it.mod }.joinToString { "${it.from.short} ${it.fromValue}→${it.to.short} ${it.toValue}" } +
                " changes=" + bb.items.count { !it.ghost && (it.equippedOn != h.id || it.mod != null) }) }
            println("%-28s builds=%d timeout=%b ms=%d nodes=%,d kept=%s %s".format(label, b.size, r.engine.timedOut, (System.nanoTime() - t0) / 1_000_000, r.engine.nodes.get(), r.engine.keptPerSlot.toList(),
                b.firstOrNull()?.final?.stats?.take(8)?.joinToString(" ") { "%.0f".format(it) } ?: ""))
        }
        val names = listOf("atk", "hp", "def", "spd", "cr", "cd", "eff", "res")
        val saved = req.mins.copyOf()
        run("as saved")
        if (System.getenv("E7_ONLY") == "1") return
        for (i in 0 until 8) if (!saved[i].isNaN()) { req.mins[i] = Double.NaN; run("without min ${names[i]} ${saved[i]}"); req.mins[i] = saved[i] }
        val capWas = req.capCrit; req.capCrit = false; run("crit cap off"); req.capCrit = capWas
        for (i in 0 until 8) req.mins[i] = Double.NaN
        run("no mins at all")
    }
}
