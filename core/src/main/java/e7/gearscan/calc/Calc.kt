package e7.gearscan.calc

import e7.gearscan.data.Bonus
import e7.gearscan.data.Content
import e7.gearscan.data.GameHero
import e7.gearscan.data.GearStat
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.SetDef
import e7.gearscan.data.Stat
import e7.gearscan.data.Stats8

const val NS = 9
const val GS = 8

/** Penetration set against a 1,500-defense target, as Fribbels assumes. */
val PEN_MULT = (1500.0 / 300 + 1) / (0.00283333 * 1500 + 1)

/**
 * A hero's stats without gear: [fixed] is everything except gear and set bonuses, already
 * multiplied by the final multipliers, as are the per-item vectors built from [itemVec].
 */
/** [setDamage]: count set damage bonuses (Rage's +30% only hits debuffed targets, so off unless asked). */
class HeroBase(val base: Stats8, val fixed: DoubleArray, val mult: DoubleArray, val setDamage: Boolean = false,
               val skill: e7.gearscan.data.SkillDmg? = null)

object Calc {
    fun heroBase(gh: GameHero, cfg: HeroConfig, artifactCode: String?, content: Content): HeroBase {
        val base = if (cfg.stars == 5 && gh.base5 != null) gh.base5 else gh.base6
        val pct = DoubleArray(3)
        val add = DoubleArray(8)
        fun apply(type: String?, v: Double) {
            when (type) {
                "att_rate" -> pct[0] += v * 100
                "max_hp_rate" -> pct[1] += v * 100
                "def_rate" -> pct[2] += v * 100
                "att" -> add[0] += v
                "max_hp" -> add[1] += v
                "def" -> add[2] += v
                "speed" -> add[3] += v
                "cri" -> add[4] += v * 100
                "cri_dmg" -> add[5] += v * 100
                "acc" -> add[6] += v * 100
                "res" -> add[7] += v * 100
            }
        }
        apply(gh.imprintType, gh.imprintValue)
        gh.ee.firstOrNull()?.let { (t, v) -> apply(t, cfg.eeValue ?: v) }
        content.artifact(artifactCode)?.let { add[0] += it.atk; add[1] += it.hp; add[2] += it.def }
        val b = cfg.bonus
        pct[0] += b[Bonus.ATK_P.ordinal]; pct[1] += b[Bonus.HP_P.ordinal]; pct[2] += b[Bonus.DEF_P.ordinal]
        add[0] += b[Bonus.ATK.ordinal]; add[1] += b[Bonus.HP.ordinal]; add[2] += b[Bonus.DEF.ordinal]
        add[3] += b[Bonus.SPD.ordinal]; add[4] += b[Bonus.CR.ordinal]; add[5] += b[Bonus.CD.ordinal]
        add[6] += b[Bonus.EFF.ordinal]; add[7] += b[Bonus.RES.ordinal]
        val mult = doubleArrayOf(1 + b[Bonus.FINAL_ATK.ordinal] / 100, 1 + b[Bonus.FINAL_HP.ordinal] / 100,
            1 + b[Bonus.FINAL_DEF.ordinal] / 100)
        val bs = base.toArray()
        val fixed = DoubleArray(8) { i -> bs[i] + add[i] + if (i < 3) bs[i] * pct[i] / 100 else 0.0 }
        for (i in 0 until 3) fixed[i] *= mult[i]
        // auto: the skill that hits hardest at the hero's own base stats
        val skill = gh.skills.firstOrNull { it.n == cfg.request?.skill } ?: gh.skills.maxByOrNull { it.damage(fixed, 1.0, 1.0) }
        return HeroBase(base, fixed, mult, cfg.request?.rageDamage == true, skill)
    }

    fun gearValue(type: GearStat, value: Double, base: Stats8, out: DoubleArray, mult: DoubleArray) {
        val i = type.stat.ordinal
        val v = if (type.percentOfBase) value / 100 * base[type.stat] else value
        out[i] += if (i < 3) v * mult[i] else v
    }

    /** Per-item contribution to final stats, plus its gear score in slot [GS]. */
    fun itemVec(it: Item, hb: HeroBase, reforged: Boolean): DoubleArray {
        val v = DoubleArray(NS)
        val ref = reforged && it.reforgeable
        gearValue(it.mainType, if (ref) Reforge.main(it.mainType, it.mainValue) else it.mainValue, hb.base, v, hb.mult)
        // a modded value already comes from the reforged table when the gear counts as reforged
        for ((i, s) in it.subs.withIndex()) gearValue(s.type, if (ref && it.mod?.index != i) s.value + Reforge.gain(s.type, s.rolls) else s.value, hb.base, v, hb.mult)
        v[GS] = it.score
        return v
    }

    /** Set bonus for final counts: 8 stat adds, then damage multiplier and penetration multiplier. */
    fun setBonus(counts: IntArray, sets: List<SetDef>, hb: HeroBase, out: DoubleArray) {
        out.fill(0.0)
        var dmg = 1.0
        var pen = 1.0
        for ((i, s) in sets.withIndex()) {
            val act = counts[i] / s.pieces
            if (act == 0 || !s.hasStats) continue
            out[0] += act * s.atkPct / 100 * hb.base.atk * hb.mult[0]
            out[1] += act * s.hpPct / 100 * hb.base.hp * hb.mult[1]
            out[2] += act * s.defPct / 100 * hb.base.def * hb.mult[2]
            out[3] += act * s.spdPct / 100 * hb.base.spd
            out[4] += act * s.cr
            out[5] += act * s.cd
            out[6] += act * s.eff
            out[7] += act * s.res
            if (hb.setDamage) dmg += act * s.dmg / 100
            if (s.pen) pen = PEN_MULT
        }
        out[8] = dmg
        out[9] = pen
    }

    class Final(val stats: DoubleArray, val dmgMult: Double, val pen: Double, val gs: Double, val skill: e7.gearscan.data.SkillDmg? = null) {
        operator fun get(s: Stat) = stats[s.ordinal]
    }

    /** Substat value as the build counts it: reforge gain added unless it is a modded value. */
    fun subValue(it: Item, i: Int, reforged: Boolean): Double {
        val s = it.subs[i]
        return if (reforged && it.reforgeable && it.mod?.index != i) s.value + Reforge.gain(s.type, s.rolls) else s.value
    }

    fun final(hb: HeroBase, items: Collection<Item>, reforged: Boolean, content: Content): Final {
        val acc = hb.fixed.copyOf(NS).also { it[GS] = 0.0 }
        val counts = IntArray(content.sets.size)
        for (it in items) {
            val v = itemVec(it, hb, reforged)
            for (x in 0 until NS) acc[x] += v[x]
            content.setIndex(it.set).takeIf { i -> i >= 0 }?.let { i -> counts[i]++ }
        }
        val sb = DoubleArray(10)
        setBonus(counts, content.sets, hb, sb)
        for (x in 0 until 8) acc[x] += sb[x]
        return Final(acc.copyOf(8), sb[8], sb[9], acc[GS], hb.skill)
    }

    fun setCounts(items: Collection<Item>, content: Content): List<Pair<SetDef, Int>> {
        val c = LinkedHashMap<String, Int>()
        for (it in items) c[it.set] = (c[it.set] ?: 0) + 1
        return c.mapNotNull { (k, n) -> content.set(k)?.let { s -> if (n >= s.pieces) s to (n / s.pieces) * s.pieces else null } }
            .sortedByDescending { it.first.pieces }
    }
}

/** Reforge gains for level 85 +15 gear, by substat roll count. */
object Reforge {
    private val plain = intArrayOf(0, 1, 3, 4, 5, 7, 8)
    private val critDmg = intArrayOf(0, 1, 2, 3, 4, 6, 7)
    private val speed = intArrayOf(0, 0, 1, 2, 3, 4, 4)

    fun gain(t: GearStat, rolls: Int): Double {
        val r = rolls.coerceIn(0, 6)
        return when (t) {
            GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.EFF, GearStat.RES -> plain[r].toDouble()
            GearStat.CR -> r.toDouble()
            GearStat.CD -> critDmg[r].toDouble()
            GearStat.SPD -> speed[r].toDouble()
            GearStat.ATK -> 11.0 * r
            GearStat.DEF -> 9.0 * r
            GearStat.HP -> 56.0 * r
        }
    }

    fun main(t: GearStat, v: Double): Double = when (t) {
        GearStat.ATK -> 525.0
        GearStat.HP -> 2835.0
        GearStat.DEF -> 310.0
        GearStat.CR -> 60.0
        GearStat.CD -> 70.0
        GearStat.SPD -> 45.0
        else -> 65.0
    }.coerceAtLeast(v)
}

enum class Rating(val label: String, val short: String) {
    DMG("Damage", "Dmg"), SPD("Speed", "Spd"), CP("CP", "CP"), EHP("EHP", "EHP"),
    ATK("Attack", "Atk"), HP("Health", "Hp"), DEF("Defense", "Def"), CR("Crit chance", "Cr"), CD("Crit dmg", "Cd"),
    EFF("Effectiveness", "Eff"), RES("Resistance", "Res"), GS("Gear score", "GS"),
    HPPS("Hp per speed", "HpS"), EHPPS("EHP per speed", "EhpS"), DMGPS("Damage per speed", "DmgS"),
    MCD("Max-crit damage", "Mcd"), MCDPS("Max-crit dmg per speed", "McdS"),
    DMGH("Damage (HP scaling)", "DmgH"), DMGD("Damage (DEF scaling)", "DmgD"),
    UPG("Upgradeable items", "Upg"), EQ("Items from others", "Eq");

    companion object {
        fun of(f: Calc.Final, upg: Int = 0, eq: Int = 0): DoubleArray {
            val s = f.stats
            val cr = minOf(s[4], 100.0) / 100
            val cd = minOf(s[5], 350.0) / 100
            val atk = s[0]; val hp = s[1]; val def = s[2]; val spd = s[3]
            val m = f.pen * f.dmgMult
            val dmg = f.skill?.damage(s, f.dmgMult, f.pen) ?: ((cr * atk * cd + (1 - cr) * atk) * m)
            val ehp = hp * (def / 300 + 1)
            val cp = ((atk * 1.6 + atk * 1.6 * cr * cd) * (1 + (spd - 45) * 0.02) + hp + def * 9.3) *
                (1 + (s[7] + s[6]) / 100 / 4)
            val mcd = atk * cd * m
            return doubleArrayOf(dmg, spd, cp, ehp, atk, hp, def, s[4], s[5], s[6], s[7], f.gs,
                hp * spd / 1000, ehp * spd / 1000, dmg * spd / 1000, mcd, mcd * spd / 1000,
                cd * hp / 10 * m, cd * def * m, upg.toDouble(), eq.toDouble())
        }
    }
}
