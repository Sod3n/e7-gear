package e7.gearscan.data

enum class Stat(val label: String, val short: String, val icon: String, val pct: Boolean) {
    ATK("Attack", "Atk", "stat-att", false),
    HP("Health", "Hp", "stat-max_hp", false),
    DEF("Defense", "Def", "stat-def", false),
    SPD("Speed", "Spd", "stat-speed", false),
    CR("Crit chance", "Cr", "stat-cri", true),
    CD("Crit dmg", "Cd", "stat-cri_dmg", true),
    EFF("Effectiveness", "Eff", "stat-acc", true),
    RES("Resistance", "Res", "stat-res", true);

    companion object {
        val ALL = entries
    }
}

/** Gear stat types as Fribbels' gear.txt names them. */
enum class GearStat(val key: String, val stat: Stat, val percentOfBase: Boolean, val short: String) {
    ATK("Attack", Stat.ATK, false, "Atk"),
    ATK_P("AttackPercent", Stat.ATK, true, "Atk%"),
    HP("Health", Stat.HP, false, "Hp"),
    HP_P("HealthPercent", Stat.HP, true, "Hp%"),
    DEF("Defense", Stat.DEF, false, "Def"),
    DEF_P("DefensePercent", Stat.DEF, true, "Def%"),
    SPD("Speed", Stat.SPD, false, "Spd"),
    CR("CriticalHitChancePercent", Stat.CR, false, "Cr"),
    CD("CriticalHitDamagePercent", Stat.CD, false, "Cd"),
    EFF("EffectivenessPercent", Stat.EFF, false, "Eff"),
    RES("EffectResistancePercent", Stat.RES, false, "Res");

    val showsPercent get() = percentOfBase || stat.pct

    fun format(v: Double): String = fmtNum(v) + if (showsPercent) "%" else ""

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String?): GearStat? = byKey[key]
    }
}

enum class Slot(val key: String, val label: String, val icon: String) {
    WEAPON("Weapon", "Weapon", "weapon"),
    HELMET("Helmet", "Helmet", "helm"),
    ARMOR("Armor", "Armor", "armor"),
    NECKLACE("Necklace", "Necklace", "neck"),
    RING("Ring", "Ring", "ring"),
    BOOTS("Boots", "Boots", "boot");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String?): Slot? = byKey[key]
        val ACCESSORIES = listOf(NECKLACE, RING, BOOTS)
    }
}

class Substat(val type: GearStat, val value: Double, val rolls: Int, val modified: Boolean)

/** A modification-stone change: substat [index] goes from [from] to [to]. */
class Mod(val index: Int, val from: GearStat, val fromValue: Double, val to: GearStat, val toValue: Double)

class Item(
    val id: Long,
    val slot: Slot,
    val set: String,
    val rank: String,
    val level: Int,
    val enhance: Int,
    val mainType: GearStat,
    val mainValue: Double,
    val subs: List<Substat>,
    var locked: Boolean,
    var equippedOn: Long?,
    val ghost: Boolean = false,
    /** Ghosts only: share of simulated pieces like this one that beat the piece it replaces. */
    val odds: Double = 0.0,
    /** Set when this is a search variant with one substat changed by a modification stone. */
    val mod: Mod? = null,
) {
    val reforgeable get() = level == 85 && enhance == 15 && !ghost

    /** Fribbels' gear score (substats only). */
    val score: Double by lazy {
        subs.sumOf { s ->
            when (s.type) {
                GearStat.ATK_P, GearStat.DEF_P, GearStat.HP_P, GearStat.EFF, GearStat.RES -> s.value
                GearStat.SPD -> s.value * 8 / 4
                GearStat.CD -> s.value * 8 / 7
                GearStat.CR -> s.value * 8 / 5
                GearStat.ATK -> s.value * 3.46 / 39
                GearStat.DEF -> s.value * 4.99 / 31
                GearStat.HP -> s.value * 3.09 / 174
            }
        }
    }
}

class OwnedHero(
    val id: Long,
    val code: String,
    val name: String,
    val stars: Int,
)

class SetDef(
    val key: String,
    val game: String,
    val name: String,
    val pieces: Int,
    val icon: String,
    val atkPct: Double, val hpPct: Double, val defPct: Double, val spdPct: Double,
    val cr: Double, val cd: Double, val eff: Double, val res: Double,
    val dmg: Double, val pen: Boolean,
) {
    /** Shows in the eight stats; Rage's damage and Penetration's defense ignore don't, so match treats them as effects. */
    val showsInStats get() = atkPct != 0.0 || hpPct != 0.0 || defPct != 0.0 || spdPct != 0.0 || cr != 0.0 || cd != 0.0 || eff != 0.0 || res != 0.0
    val hasStats get() = atkPct != 0.0 || hpPct != 0.0 || defPct != 0.0 || spdPct != 0.0 || cr != 0.0 ||
        cd != 0.0 || eff != 0.0 || res != 0.0 || dmg != 0.0 || pen
}

class Stats8(val atk: Double, val hp: Double, val def: Double, val spd: Double,
             val cr: Double, val cd: Double, val eff: Double, val res: Double) {
    operator fun get(s: Stat) = when (s) {
        Stat.ATK -> atk; Stat.HP -> hp; Stat.DEF -> def; Stat.SPD -> spd
        Stat.CR -> cr; Stat.CD -> cd; Stat.EFF -> eff; Stat.RES -> res
    }

    fun toArray() = doubleArrayOf(atk, hp, def, spd, cr, cd, eff, res)

    companion object {
        fun of(a: DoubleArray) = Stats8(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7])
        val ZERO = Stats8(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

class GameHero(
    val code: String,
    val name: String?,
    val element: String?,
    val role: String?,
    val base6: Stats8,
    val base5: Stats8?,
    val imprintType: String?,
    val imprintValue: Double,
    val ee: List<Pair<String, Double>>,
    val skills: List<SkillDmg> = emptyList(),
)

/**
 * One skill's average hit from the game's skill tables (the helper's damage calculator):
 * (atk × rate + own hp × hp + own def × def) × pow × (set damage + own spd × spd + own hp × hpMul) × crit.
 * Target-side terms don't depend on gear and are left out; the target's defense is a constant factor.
 */
class SkillDmg(val n: Int, val rate: Double, val pow: Double, val hp: Double = 0.0, val def: Double = 0.0,
               val spd: Double = 0.0, val hpMul: Double = 0.0, val noCrit: Boolean = false) {
    /** Ranks builds exactly like plain attack × crit damage, so the search can keep its fast bound. */
    val attackOnly get() = hp == 0.0 && def == 0.0 && spd == 0.0 && hpMul == 0.0 && !noCrit

    fun damage(s: DoubleArray, dmgMult: Double, pen: Double): Double {
        val cr = if (noCrit) 0.0 else minOf(s[4], 100.0) / 100
        val cd = minOf(s[5], 350.0) / 100
        return (s[0] * rate + s[1] * hp + s[2] * def) * (dmgMult + s[3] * spd + s[1] * hpMul) * pow * (cr * cd + (1 - cr)) * pen
    }
}

class Artifact(val code: String, val name: String, val role: String?, val atk: Double, val hp: Double, val def: Double, val rarity: Int = 5)

fun fmtNum(v: Double): String {
    val r = Math.round(v * 10) / 10.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
}

fun fmtInt(v: Double): String = "%,d".format(java.util.Locale.US, Math.round(v))

fun fmtK(v: Double): String = when {
    v >= 100_000 -> "${Math.round(v / 1000)}k"
    v >= 1000 -> "%.1fk".format(java.util.Locale.US, v / 1000).replace(".0k", "k")
    else -> Math.round(v).toString()
}

fun fmtSigned(v: Double): String = (if (v >= 0) "+" else "−") + fmtInt(Math.abs(v))
