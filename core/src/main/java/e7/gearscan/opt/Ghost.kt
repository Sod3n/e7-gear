package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.calc.Reforge
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat
import kotlin.random.Random

/**
 * Ghost pieces: Epic level 85 +15 gear simulated with the game's roll ranges (4 substats,
 * 5 upgrades on random substats), among pieces a player would enhance (3+ substats the goal
 * values). Of the simulated pieces that beat the current one, the ghost is the typical, top 25%
 * or top 5% one; the share that beats it is the farming odds.
 */
object Ghost {
    val QUANTILES = doubleArrayOf(0.5, 0.75, 0.95)
    val QUALITY_LABELS = listOf("Typical +15", "Good · top 25%", "Great · top 5%")

    private val ranges = mapOf(
        GearStat.ATK_P to (4 to 8), GearStat.HP_P to (4 to 8), GearStat.DEF_P to (4 to 8),
        GearStat.EFF to (4 to 8), GearStat.RES to (4 to 8), GearStat.CR to (3 to 5), GearStat.CD to (4 to 7),
        GearStat.SPD to (2 to 4), GearStat.ATK to (33 to 47), GearStat.HP to (157 to 202), GearStat.DEF to (28 to 35),
    )

    private val mainValue = mapOf(
        GearStat.ATK to 500.0, GearStat.HP to 2700.0, GearStat.DEF to 300.0, GearStat.CR to 55.0, GearStat.CD to 65.0,
        GearStat.SPD to 40.0, GearStat.ATK_P to 60.0, GearStat.HP_P to 60.0, GearStat.DEF_P to 60.0,
        GearStat.EFF to 60.0, GearStat.RES to 60.0,
    )

    val ACCESSORY_MAINS = mapOf(
        Slot.NECKLACE to listOf(GearStat.CD, GearStat.CR, GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.ATK, GearStat.HP, GearStat.DEF),
        Slot.RING to listOf(GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.EFF, GearStat.RES, GearStat.ATK, GearStat.HP, GearStat.DEF),
        Slot.BOOTS to listOf(GearStat.SPD, GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.ATK, GearStat.HP, GearStat.DEF),
    )

    fun mainsFor(slot: Slot): List<GearStat> = when (slot) {
        Slot.WEAPON -> listOf(GearStat.ATK)
        Slot.HELMET -> listOf(GearStat.HP)
        Slot.ARMOR -> listOf(GearStat.DEF)
        else -> ACCESSORY_MAINS.getValue(slot)
    }

    /** A +15 piece per slot with only its main (the requested one, else the most wanted) and the first set choice: 0% of match. */
    fun mainsOnly(req: e7.gearscan.data.Request): List<Item> {
        val sets = req.setAlts.firstOrNull().orEmpty().entries.sortedByDescending { it.value }.flatMap { (k, n) -> List(n) { k } }
        return Slot.entries.mapIndexed { i, slot ->
            val main = req.mains[slot]?.firstOrNull() ?: mainsFor(slot).maxBy {
                req.weights[it.stat.ordinal] * 2 + if (it.percentOfBase || it.stat.pct || it == GearStat.SPD) 1 else 0
            }
            val mv = mainValue.getValue(main).let { if (req.reforged) Reforge.main(main, it) else it }
            Item(nextId--, slot, sets.getOrElse(i) { "" }, "Epic", if (req.reforged) 90 else 85, 15, main, mv, emptyList(), false, null, ghost = true)
        }
    }

    private fun subPool(slot: Slot, main: GearStat) = GearStat.entries.filter {
        it != main && !(slot == Slot.WEAPON && it.stat == e7.gearscan.data.Stat.DEF) &&
            !(slot == Slot.ARMOR && it.stat == e7.gearscan.data.Stat.ATK)
    }

    private var nextId = -1L

    /** The [quantile] piece among simulations scoring above [baseline], or null if none does. */
    fun piece(slot: Slot, set: String, main: GearStat, quantile: Double, score: (Item) -> Double, baseline: Double,
              reforged: Boolean, useful: Set<GearStat>, sims: Int = 4000, seed: Int = 7): Item? {
        val rnd = Random(seed + slot.ordinal * 31 + main.ordinal * 7 + set.hashCode())
        val pool = subPool(slot, main)
        val need = minOf(3, pool.count { it in useful })
        val mv = mainValue.getValue(main).let { if (reforged) Reforge.main(main, it) else it }
        val scored = (0 until sims).map {
            var picked = pool.shuffled(rnd).take(4)
            var tries = 0
            while (picked.count { it in useful } < need && tries++ < 200) picked = pool.shuffled(rnd).take(4)
            val rolls = IntArray(4) { 1 }
            repeat(5) { rolls[rnd.nextInt(4)]++ }
            val subs = picked.mapIndexed { i, t ->
                val (lo, hi) = ranges.getValue(t)
                var v = 0.0
                repeat(rolls[i]) { v += rnd.nextInt(lo, hi + 1) }
                if (reforged) v += Reforge.gain(t, rolls[i])
                Substat(t, v, rolls[i], false)
            }
            val p = Item(0, slot, set, "Epic", if (reforged) 90 else 85, 15, main, mv, subs, false, null, ghost = true)
            p to score(p)
        }
        val better = scored.filter { it.second > baseline + 1e-9 }.sortedBy { it.second }
        if (better.isEmpty()) return null
        val p = better[((better.size - 1) * quantile).toInt()].first
        return Item(nextId--, p.slot, p.set, p.rank, p.level, p.enhance, p.mainType, p.mainValue, p.subs, false, null,
            ghost = true, odds = better.size.toDouble() / sims)
    }

    /**
     * Ghost candidates around [best]: for each allowed slot, the sets the request needs (or the set
     * already there) times the mains worth trying, scored by the goal's gain when swapped into [best].
     */
    fun candidates(setup: Setup, best: Build, reforged: Boolean, baseline: Double = best.value): List<Item> {
        val req = setup.req
        val needed = req.setAlts.flatMap { it.keys }.toSet()
        val out = ArrayList<Item>()
        val obj = Objectives.of(req, setup.hb)
        val useful = usefulStats(setup, best, obj)
        for (slot in req.ghostSlots) {
            val inSlot = best.items.firstOrNull { it.slot == slot } ?: continue
            val sets = (needed + inSlot.set).filter { set ->
                setup.content.setIndex(set) >= 0 && setsOk(req, best.items.map { if (it.slot == slot) set else it.set })
            }
            val mains = req.mains[slot]?.takeIf { it.isNotEmpty() }?.toList()
                ?: mainsFor(slot).let { all -> if (all.size == 1) all else rankMains(setup, best, slot, all, obj).take(3) }
            for (set in sets) for (main in mains) {
                piece(slot, set, main, QUANTILES[req.ghostQuality.coerceIn(0, 2)], { g ->
                    val items = best.items.map { if (it.slot == slot) g else it }
                    val f = Calc.final(setup.hb, items, reforged, setup.content)
                    if (minsOk(req, f.stats)) obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen) else Double.NEGATIVE_INFINITY
                }, baseline, reforged, useful)?.let { out += it }
            }
        }
        return out
    }

    private fun setsOk(req: e7.gearscan.data.Request, sets: List<String>): Boolean {
        if (req.setAlts.isEmpty()) return true
        val c = sets.groupingBy { it }.eachCount()
        return req.setAlts.any { alt -> alt.all { (k, n) -> (c[k] ?: 0) >= n } }
    }

    private fun minsOk(req: e7.gearscan.data.Request, s: DoubleArray) =
        (0 until 8).all { req.mins[it].isNaN() || s[it] >= req.mins[it] } && (0 until 8).all { req.maxs[it].isNaN() || s[it] <= req.maxs[it] }

    /** Substat types that raise the goal when added to [best]: what a player would want to see on a fresh piece. */
    fun usefulStats(setup: Setup, best: Build, obj: Objective): Set<GearStat> {
        val f = Calc.final(setup.hb, best.items, setup.req.reforged, setup.content)
        val s0 = f.stats + doubleArrayOf(0.0)
        val base = obj.eval(s0, f.dmgMult, f.pen)
        return GearStat.entries.filter { t ->
            val v = DoubleArray(9)
            Calc.gearValue(t, ranges.getValue(t).second * 2.0, setup.hb.base, v, setup.hb.mult)
            val s = DoubleArray(9) { s0[it] + v[it] }
            obj.eval(s, f.dmgMult, f.pen) > base + 1e-9
        }.toSet()
    }

    private fun rankMains(setup: Setup, best: Build, slot: Slot, all: List<GearStat>, obj: Objective): List<GearStat> {
        val base = best.items.filter { it.slot != slot }
        val set = best.items.first { it.slot == slot }.set
        return all.sortedByDescending { m ->
            val g = Item(0, slot, set, "Epic", 90, 15, m, mainValue.getValue(m), emptyList(), false, null, ghost = true)
            val f = Calc.final(setup.hb, base + g, setup.req.reforged, setup.content)
            obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
        }
    }
}
