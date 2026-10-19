package e7.gearscan

import e7.gearscan.calc.Calc
import e7.gearscan.calc.HeroBase
import e7.gearscan.calc.NS
import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.opt.Candidate
import e7.gearscan.opt.Engine
import e7.gearscan.opt.Objective
import e7.gearscan.opt.Objectives
import e7.gearscan.data.Request
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Engine exactness on a real box. Needs E7_GEAR=<gear.txt>; Frieren with plain lv60 6-star base
 * stats and no other bonuses reproduces the Java benchmark's best values.
 */
class EngineTest {
    private val content = TestContent.content
    private val gearPath = System.getenv("E7_GEAR")

    private fun setup(): Pair<HeroBase, List<List<Candidate>>> {
        val box = Box.parse(File(gearPath!!).readText())
        val g = content.heroes.values.first { it.name == "Frieren" }
        val hb = HeroBase(g.base6, g.base6.toArray(), doubleArrayOf(1.0, 1.0, 1.0))
        val items = box.items.filter { it.enhance >= 12 && content.setIndex(it.set) >= 0 }
        val slots = e7.gearscan.data.Slot.entries.map { s ->
            items.withIndex().filter { it.value.slot == s }.map { (i, it) ->
                Candidate(i, Calc.itemVec(it, hb, false), content.setIndex(it.set), false)
            }
        }
        return hb to slots
    }

    private fun engine(hb: HeroBase, slots: List<List<Candidate>>, obj: Objective, mins: DoubleArray,
                       alts: List<IntArray> = emptyList(), maxs: DoubleArray = DoubleArray(8) { Double.NaN }, k: Int = 1) =
        Engine(slots, hb.fixed, doubleArrayOf(hb.base.atk, hb.base.hp, hb.base.def, hb.base.spd), content.sets, obj,
            mins, maxs, alts, 0, k)

    private fun mins(spd: Double = Double.NaN, cr: Double = Double.NaN) =
        DoubleArray(8) { Double.NaN }.also { it[3] = spd; it[4] = cr }

    @Test
    fun matchesJavaBenchmark() {
        assumeTrue(gearPath != null)
        val (hb, slots) = setup()
        val des = IntArray(content.sets.size).also { it[content.setIndex("DestructionSet")] = 4 }
        val cases = listOf(
            Triple("dmg", engine(hb, slots, Objectives.damage(), mins()), 21407.0),
            Triple("ehp", engine(hb, slots, Objectives.ehp(), mins()), 151731.0),
            Triple("dmg spd200 cr80", engine(hb, slots, Objectives.damage(), mins(200.0, 80.0)), 17296.0),
            Triple("dmg spd200 cr80 des", engine(hb, slots, Objectives.damage(), mins(200.0, 80.0), listOf(des)), 17021.0),
            Triple("ehp spd200", engine(hb, slots, Objectives.ehp(), mins(200.0)), 134434.0),
        )
        for ((name, e, want) in cases) {
            val t = System.nanoTime()
            e.run(4)
            val got = e.results().first().value
            println("%-22s best=%.0f  %.0f ms  kept=%s".format(name, got, (System.nanoTime() - t) / 1e6, e.keptPerSlot.toList()))
            assertEquals(name, want, got, 1.0)
        }
    }

    @Test
    fun targetsSpeed() {
        assumeTrue(gearPath != null)
        val (hb, slots) = setup()
        val req = Request().apply {
            targets[0] = 4400.0; targets[3] = 250.0; targets[4] = 100.0; targets[5] = 302.0
            weights[0] = 3; weights[3] = 3; weights[4] = 3; weights[5] = 2
        }
        val alt = IntArray(content.sets.size).also { it[content.setIndex("SpeedSet")] = 4; it[content.setIndex("CriticalSet")] = 2 }
        for ((name, alts) in listOf("targets" to emptyList(), "targets spd4+crit2" to listOf(alt))) {
            for (k in listOf(1, 50)) {
                val e = engine(hb, slots, Objectives.targets(req, hb), mins(), alts, k = k)
                e.deadline = System.nanoTime() + 60_000_000_000
                val t = System.nanoTime()
                e.run(4)
                println("%-20s k=%-3d best=%.4f  %.0f ms  nodes=%,d timeout=%b".format(name, k, e.results().first().value,
                    (System.nanoTime() - t) / 1e6, e.nodes.get(), e.timedOut))
            }
        }
    }

    /** Top-K on small slices (best 7 per slot) against brute force, for every goal and constraint kind. */
    @Test
    fun matchesBruteForce() {
        assumeTrue(gearPath != null)
        val (hb, all) = setup()
        val req = Request().apply {
            targets[3] = 230.0; targets[4] = 100.0; targets[5] = 280.0; targets[0] = 4000.0
            weights[3] = 3; weights[4] = 2
        }
        val tor = IntArray(content.sets.size).also { it[content.setIndex("SpeedSet")] = 4; it[content.setIndex("TorrentSet")] = 2 }
        val crit = IntArray(content.sets.size).also { it[content.setIndex("CriticalSet")] = 2 }
        val objs = listOf("dmg" to Objectives.damage(), "ehp" to Objectives.ehp(), "cp" to Objectives.cp(),
            "targets" to Objectives.targets(req, hb), "spd" to Objectives.speed(), "spd then targets" to Objectives.speedThenTargets(req, hb),
            // skills scaling off own hp, def or speed, and one that can't crit (Krau S2, Corvus S1, Vildred S3, Krau S3)
            "skill hp" to Objectives.skill(e7.gearscan.data.SkillDmg(2, 0.8, 1.3, hp = 0.13)),
            "skill def" to Objectives.skill(e7.gearscan.data.SkillDmg(1, 0.7, 1.3, def = 0.9)),
            "skill spd" to Objectives.skill(e7.gearscan.data.SkillDmg(3, 0.85, 1.3, spd = 0.001125)),
            "skill no crit" to Objectives.skill(e7.gearscan.data.SkillDmg(3, 0.3, 1.0, noCrit = true)))
        for (slice in 0 until 3) {
            val slots = all.map { li -> li.sortedByDescending { it.vec[slice * 2 % 8] + it.vec[5] }.take(7) }
            for ((name, obj) in objs) for ((cname, mins, alts, maxs) in listOf(
                Quad("none", mins(), emptyList(), DoubleArray(8) { Double.NaN }),
                Quad("spd180", mins(180.0), emptyList(), DoubleArray(8) { Double.NaN }),
                Quad("spd4+tor2|crit", mins(), listOf(tor, crit), DoubleArray(8) { Double.NaN }),
                Quad("cr<=90", mins(), emptyList(), DoubleArray(8) { Double.NaN }.also { it[4] = 90.0 }),
                // several minimums that compete for the same pieces: what the joint-minimums cut is for
                Quad("atk+spd+cd+eff", jointMins(hb, slots, 0.55), emptyList(), DoubleArray(8) { Double.NaN }),
                Quad("atk+spd+cd+eff with sets", jointMins(hb, slots, 0.5), listOf(tor, crit), DoubleArray(8) { Double.NaN }),
            )) {
                val e = engine(hb, slots, obj, mins, alts, maxs, k = 10)
                e.run(2)
                val got = e.results().map { it.value }
                val want = brute(hb, slots, obj, mins, alts, maxs, 10)
                assertEquals("$name/$cname/$slice count", want.size, got.size)
                for (i in want.indices) assertEquals("$name/$cname/$slice #$i", want[i], got[i], 1e-6 * maxOf(1.0, Math.abs(want[i])))
            }
        }
    }

    @Test
    fun matchesBruteForceWithCosts() {
        // one-hero searches pay for modifications and pool runs for moves, both as a cost per piece
        assumeTrue(gearPath != null)
        val (hb, all) = setup()
        val req = Request().apply { targets[3] = 230.0; targets[4] = 100.0; targets[5] = 280.0; targets[0] = 4000.0; weights[3] = 3 }
        val objs = listOf("dmg" to Objectives.damage(), "targets" to Objectives.targets(req, hb), "spd" to Objectives.speed(),
            "skill spd" to Objectives.skill(e7.gearscan.data.SkillDmg(3, 0.85, 1.3, spd = 0.001125)))
        val none = DoubleArray(8) { Double.NaN }
        for (slice in 0 until 3) {
            val plain = all.map { li -> li.sortedByDescending { it.vec[slice * 2 % 8] + it.vec[5] }.take(7) }
            for ((name, obj) in objs) {
                val top = brute(hb, plain, obj, mins(), emptyList(), none, 10)
                // big enough to reorder the top 10: a third of their spread per costed piece
                val each = maxOf(1e-6, (top.first() - top.last()) / 3)
                val slots = plain.map { li -> li.mapIndexed { i, c -> Candidate(c.ref, c.vec, c.set, c.ghost, c.changed, if (i % 3 == 1) each else 0.0) } }
                for ((cname, mins) in listOf("none" to mins(), "spd180" to mins(180.0))) {
                    val e = engine(hb, slots, obj, mins, emptyList(), none, k = 10)
                    e.run(2)
                    val got = e.results().map { it.value }
                    val want = brute(hb, slots, obj, mins, emptyList(), none, 10)
                    assertEquals("$name/$cname/$slice count", want.size, got.size)
                    for (i in want.indices) assertEquals("$name/$cname/$slice #$i", want[i], got[i], 1e-6 * maxOf(1.0, Math.abs(want[i])))
                }
            }
        }
    }

    /** Minimums [at] of the way from each stat's lowest to highest reachable total on [slots]. */
    private fun jointMins(hb: HeroBase, slots: List<List<Candidate>>, at: Double) = DoubleArray(8) { Double.NaN }.also { m ->
        for (x in intArrayOf(0, 3, 5, 6)) {
            val lo = hb.fixed[x] + slots.sumOf { li -> li.minOf { it.vec[x] } }
            val hi = hb.fixed[x] + slots.sumOf { li -> li.maxOf { it.vec[x] } }
            m[x] = lo + at * (hi - lo)
        }
    }

    private data class Quad(val a: String, val b: DoubleArray, val c: List<IntArray>, val d: DoubleArray)

    private fun brute(hb: HeroBase, slots: List<List<Candidate>>, obj: Objective, mins: DoubleArray,
                      alts: List<IntArray>, maxs: DoubleArray, k: Int): List<Double> {
        val vals = ArrayList<Double>()
        val acc = DoubleArray(NS)
        val cnt = IntArray(content.sets.size)
        val sb = DoubleArray(10)
        var paid = 0.0
        fun rec(d: Int) {
            if (d == 6) {
                Calc.setBonus(cnt, content.sets, hb, sb)
                val s = DoubleArray(NS) { x -> if (x < 8) hb.fixed[x] + acc[x] + sb[x] else acc[x] }
                for (x in 0 until 8) {
                    if (!mins[x].isNaN() && s[x] < mins[x]) return
                    if (!maxs[x].isNaN() && s[x] > maxs[x]) return
                }
                if (alts.isNotEmpty() && alts.none { a -> a.indices.all { cnt[it] >= a[it] } }) return
                vals += obj.eval(s, sb[8], sb[9]) - paid
                return
            }
            for (c in slots[d]) {
                for (x in 0 until NS) acc[x] += c.vec[x]
                cnt[c.set]++; paid += c.cost
                rec(d + 1)
                cnt[c.set]--; paid -= c.cost
                for (x in 0 until NS) acc[x] -= c.vec[x]
            }
        }
        rec(0)
        return vals.sortedDescending().take(k)
    }
}
