package e7.gearscan.opt

import e7.gearscan.calc.Calc
import e7.gearscan.calc.GS
import e7.gearscan.calc.HeroBase
import e7.gearscan.calc.Rating
import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.data.GearStat
import e7.gearscan.data.GameHero
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.Request
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat

object Objectives {
    private val ALL8 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    fun damage() = object : Objective {
        override val uses = intArrayOf(0, 4, 5)
        override val product = 1
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double): Double {
            val cr = minOf(s[4], 100.0) / 100
            val cd = minOf(s[5], 350.0) / 100
            return (cr * s[0] * cd + (1 - cr) * s[0]) * pen * dmgMult
        }
    }

    /** A skill that also scales off own hp, def or speed (or can't crit): general bound, exact since damage only grows with each stat. */
    fun skill(sk: e7.gearscan.data.SkillDmg) = object : Objective {
        override val uses = listOfNotNull(0.takeIf { sk.rate > 0 }, 1.takeIf { sk.hp > 0 || sk.hpMul > 0 }, 2.takeIf { sk.def > 0 },
            3.takeIf { sk.spd > 0 }, 4.takeIf { !sk.noCrit }, 5.takeIf { !sk.noCrit }).toIntArray()
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double) = sk.damage(s, dmgMult, pen)
    }

    fun ehp() = object : Objective {
        override val uses = intArrayOf(1, 2)
        override val product = 2
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double) = s[1] * (s[2] / 300 + 1)
    }

    fun speed() = object : Objective {
        override val uses = intArrayOf(3, GS)
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double) = s[3] + 1e-4 * s[GS]
    }

    /** Fastest first; among equally fast builds the one closest to the targets (a whole speed point outweighs any target gap). */
    fun speedThenTargets(req: Request, hb: HeroBase): Objective {
        val t = targets(req, hb)
        return object : Objective {
            override val uses = (intArrayOf(3) + t.uses).distinct().toIntArray()
            override fun eval(s: DoubleArray, dmgMult: Double, pen: Double) =
                s[3] + 0.9 * (1 + maxOf(-1000.0, t.eval(s, dmgMult, pen)) / 1000)
        }
    }

    fun gearScore() = object : Objective {
        override val uses = intArrayOf(GS)
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double) = s[GS]
    }

    fun cp() = object : Objective {
        override val uses = ALL8
        override fun eval(s: DoubleArray, dmgMult: Double, pen: Double): Double {
            val cr = minOf(s[4], 100.0) / 100
            val cd = minOf(s[5], 350.0) / 100
            return ((s[0] * 1.6 + s[0] * 1.6 * cr * cd) * (1 + (s[3] - 45) * 0.02) + s[1] + s[2] * 9.3) *
                (1 + (s[7] + s[6]) / 400)
        }
    }

    /** Roll-sized units, so a miss of one roll costs the same on every stat. */
    fun rollSize(hb: HeroBase) = doubleArrayOf(hb.base.atk * 0.08 * hb.mult[0], hb.base.hp * 0.08 * hb.mult[1],
        hb.base.def * 0.08 * hb.mult[2], 4.0, 5.0, 7.0, 8.0, 8.0)

    /** Minus the weighted shortfall below each target, in rolls; going over earns nothing. */
    fun targets(req: Request, hb: HeroBase): Objective {
        val idx = (0 until 8).filter { !req.targets[it].isNaN() }
        val t = req.targets.copyOf()
        val w = DoubleArray(8) { maxOf(1, req.weights[it]).toDouble() }
        val scale = rollSize(hb)
        return object : Objective {
            override val uses = (idx + GS).toIntArray()
            override val targets = TargetSpec(idx.toIntArray(), DoubleArray(idx.size) { t[idx[it]] },
                DoubleArray(idx.size) { w[idx[it]] / scale[idx[it]] }, 1e-4)
            override fun eval(s: DoubleArray, dmgMult: Double, pen: Double): Double {
                var miss = 0.0
                for (i in idx) if (s[i] < t[i]) miss += w[i] * (t[i] - s[i]) / scale[i]
                return -miss + 1e-4 * s[GS]
            }
        }
    }

    fun of(req: Request, hb: HeroBase): Objective = when (req.goal) {
        Goal.TARGETS -> targets(req, hb)
        Goal.DAMAGE -> hb.skill?.takeUnless { it.attackOnly }?.let { skill(it) } ?: damage()
        Goal.EHP -> ehp()
        Goal.SPEED -> if (req.targets.any { !it.isNaN() }) speedThenTargets(req, hb) else speed()
        Goal.CP -> cp()
        Goal.GEAR_SCORE -> gearScore()
    }
}

class Build(
    val items: List<Item>,
    val value: Double,
    val final: Calc.Final,
    val ratings: DoubleArray,
    val fromOthers: Int,
) {
    val ghost: Item? get() = items.firstOrNull { it.ghost }
    fun rating(r: Rating) = ratings[r.ordinal]
}

/** Everything one hero's search needs. */
/** Default crit chance cap: one roll of slack over 100, since a hard 100 drops near-perfect builds (Rhianna's best has 104). */
const val CRIT_CAP = 105.0

/** Mods kept per item; more makes the search much slower for little gain (measured on a real box). */
var MODS_PER_ITEM = 2

class Setup(
    val hero: OwnedHero,
    val game: GameHero,
    val cfg: HeroConfig,
    val req: Request,
    val box: Box,
    val content: Content,
    /** Whether gear worn by this hero may be taken (gear tiers: only from lower tiers). */
    val canTakeFrom: (Long) -> Boolean,
    val excluded: Set<Long> = emptySet(),
) {
    val artifact get() = cfg.artifact ?: req.artifact
    val hb: HeroBase by lazy { Calc.heroBase(game, cfg, artifact, content) }

    fun eligible(it: Item): Boolean {
        if (it.id in excluded) return false
        val own = it.equippedOn == hero.id
        if (!own) {
            if (it.enhance < req.minEnhance) return false
            if (!req.allowLocked && it.locked) return false
            val holder = it.equippedOn
            if (req.lowerPriorityOnly && holder != null && !canTakeFrom(holder)) return false
        }
        val m = req.mains[it.slot]
        if (!m.isNullOrEmpty() && it.mainType !in m) return false
        return true
    }

    fun current(): Map<Slot, Item> = box.gearOf(hero.id)

    /** The request's maximums plus the default crit chance cap (a hand-set max wins). */
    fun maxs(): DoubleArray = req.maxs.copyOf().also {
        if (req.capCrit && it[Stat.CR.ordinal].isNaN()) it[Stat.CR.ordinal] = CRIT_CAP + 1e-6
    }

    /** Stats a mod may create: what the targets ask for, else what the goal is about. */
    fun modWanted(): Set<GearStat> {
        val fromTargets = Stat.entries.filter { req.weights[it.ordinal] > 0 && !req.targets[it.ordinal].isNaN() }
        val stats = fromTargets.ifEmpty {
            when (req.goal) {
                Goal.DAMAGE -> listOf(Stat.ATK, Stat.CR, Stat.CD)
                Goal.EHP -> listOf(Stat.HP, Stat.DEF)
                Goal.SPEED -> listOf(Stat.SPD)
                else -> Stat.entries
            }
        }
        return stats.map { s -> GearStat.entries.firstOrNull { it.stat == s && (it.percentOfBase || s.pct || s == Stat.SPD) } ?: GearStat.entries.first { it.stat == s } }.toSet()
    }

    fun modVariants(items: List<Item>): List<Item> {
        if (!req.mods) return emptyList()
        val wanted = modWanted()
        val q = listOf(0.0, 0.5, 1.0)[req.modQuality.coerceIn(0, 2)]
        val roll = Objectives.rollSize(hb)
        // only each item's most useful mods (rolls of wanted stat gained, by weight); every mod of every item makes the search crawl
        fun worth(v: Item) = v.mod!!.let { m ->
            val out = DoubleArray(roll.size + 1).also { Calc.gearValue(m.to, m.toValue, hb.base, it, hb.mult) }
            maxOf(1, req.weights[m.to.stat.ordinal]) * out[m.to.stat.ordinal] / roll[m.to.stat.ordinal]
        }
        return items.flatMap { Mods.variants(it, wanted, { t -> t !in wanted }, req.modMaxRolls, req.modGreater, q, req.reforged)
            .sortedByDescending(::worth).take(MODS_PER_ITEM) }
    }

    fun build(items: List<Item>, value: Double = Double.NaN): Build {
        val f = Calc.final(hb, items, req.reforged, content)
        val eq = items.count { !it.ghost && it.equippedOn != null && it.equippedOn != hero.id }
        val upg = items.count { it.enhance < 15 }
        return Build(items, value, f, Rating.of(f, upg, eq), eq)
    }
}

class SearchRun(
    private val setup: Setup,
    private val k: Int,
    private val extra: List<Item> = emptyList(),
    private val maxGhost: Int = 0,
    private val startThreshold: Double = Double.NEGATIVE_INFINITY,
    /** Taken off a build's value for each piece, in the objective's units; >= 0. */
    private val cost: (Item) -> Double = { 0.0 },
) {
    private val pool: List<Item>
    val engine: Engine
    val objective: Objective

    init {
        val req = setup.req
        val hb = setup.hb
        val content = setup.content
        val cur = setup.current()
        val own = setup.box.items.filter { setup.eligible(it) }
        pool = own + setup.modVariants(own) + extra
        val slots = Slot.entries.map { s ->
            val fixedItem = if (req.keepCurrent) cur[s] else null
            pool.withIndex().filter { (_, it) -> it.slot == s && (fixedItem == null || it.id == fixedItem.id || it.ghost) }
                .mapNotNull { (i, it) ->
                    val si = content.setIndex(it.set)
                    if (si < 0) null else Candidate(i, Calc.itemVec(it, hb, req.reforged), si, it.ghost, !it.ghost && (it.id != cur[s]?.id || it.mod != null), cost(it))
                }
        }
        val setBase = doubleArrayOf(hb.base.atk * hb.mult[0], hb.base.hp * hb.mult[1], hb.base.def * hb.mult[2], hb.base.spd)
        val alts = req.setAlts.map { alt ->
            IntArray(content.sets.size).also { a -> alt.forEach { (key, n) -> content.setIndex(key).takeIf { it >= 0 }?.let { a[it] = n } } }
        }
        objective = Objectives.of(req, hb)
        engine = Engine(slots, hb.fixed, setBase, content.sets, objective, req.mins, setup.maxs(), alts, maxGhost, k, startThreshold, req.maxChanges, hb.setDamage)
    }

    fun run(threads: Int, limitMs: Long) {
        engine.deadline = System.nanoTime() + limitMs * 1_000_000
        engine.run(threads)
    }

    fun cancel() { engine.cancelled = true }

    fun builds(): List<Build> = engine.results().map { e -> setup.build(e.picks.map { pool[it] }, e.value) }
}

object Team {
    /** Heroes in order; each takes its best build, and its pieces are off limits to the heroes after it. */
    fun search(setups: List<(Set<Long>) -> Setup>, threads: Int, limitMs: Long, cancelled: () -> Boolean): List<Build?> {
        val taken = HashSet<Long>()
        val out = ArrayList<Build?>()
        for (make in setups) {
            if (cancelled()) { out += null; continue }
            val s = make(taken)
            val run = SearchRun(s, 1)
            run.run(threads, limitMs)
            val b = run.builds().firstOrNull()
            b?.items?.forEach { taken += it.id }
            out += b
        }
        return out
    }
}

fun Stat.of(b: Build) = b.final.stats[ordinal]
