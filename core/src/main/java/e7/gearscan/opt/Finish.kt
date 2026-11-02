package e7.gearscan.opt

import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.data.Substat
import kotlin.random.Random

/**
 * Simulated +15 finishes of a piece below +15, with the game's rules as checked on players' roll histories: every +3 rolls
 * one existing line with equal chance; a Heroic piece gets its fourth line at +12 instead; rolls are uniform in the
 * level's range (speed and flat stats round down, so their table maximum almost never shows and is left out).
 */
object Finish {
    private val roll85 =
        mapOf(
            GearStat.SPD to (2 to 4),
            GearStat.CR to (3 to 5),
            GearStat.CD to (4 to 7),
            GearStat.ATK_P to (4 to 8),
            GearStat.HP_P to (4 to 8),
            GearStat.DEF_P to (4 to 8),
            GearStat.EFF to (4 to 8),
            GearStat.RES to (4 to 8),
            GearStat.ATK to (33 to 46),
            GearStat.HP to (157 to 202),
            GearStat.DEF to (28 to 34),
        )
    private val roll88 =
        mapOf(
            GearStat.SPD to (3 to 4),
            GearStat.CR to (3 to 6),
            GearStat.CD to (4 to 8),
            GearStat.ATK_P to (5 to 9),
            GearStat.HP_P to (5 to 9),
            GearStat.DEF_P to (5 to 9),
            GearStat.EFF to (5 to 9),
            GearStat.RES to (5 to 9),
            GearStat.ATK to (37 to 53),
            GearStat.HP to (178 to 229),
            GearStat.DEF to (32 to 39),
        )

    /** Ordinary roll range, shared by sampling and the joint probability search. */
    fun range(
        type: GearStat,
        level: Int,
    ): IntRange {
        val (lo, hi) = (if (level >= 88) roll88 else roll85).getValue(type)
        return lo..hi
    }

    /** Inclusive game maximum, including rare endpoint rolls omitted from ordinary odds. */
    fun maximumRoll(
        type: GearStat,
        level: Int,
    ): Int =
        when (type) {
            GearStat.SPD -> 5
            GearStat.DEF -> range(type, level).last + 1
            else -> range(type, level).last
        }

    private val main15Typical =
        mapOf(
            GearStat.ATK to 500.0,
            GearStat.HP to 2700.0,
            GearStat.DEF to 300.0,
            GearStat.CR to 55.0,
            GearStat.CD to 65.0,
            GearStat.SPD to 40.0,
        )

    fun main15(it: Item): Double = if (!it.main15.isNaN()) it.main15 else main15Typical[it.mainType] ?: 60.0

    /** [n] finishes of [item]; each keeps the piece's id. */
    fun of(
        item: Item,
        n: Int,
        rnd: Random = Random(item.id.hashCode()),
    ): List<Item> {
        val roll = if (item.level >= 88) roll88 else roll85
        val pool =
            GearStat.entries.filter { t ->
                t != item.mainType &&
                    !(item.slot == Slot.WEAPON && t.stat == Stat.DEF) &&
                    !(item.slot == Slot.ARMOR && t.stat == Stat.ATK)
            }
        val main = main15(item)
        return List(n) {
            val types = item.subs.map { s -> s.type }.toMutableList()
            val vals = item.subs.map { s -> s.value }.toMutableList()
            val rolls = item.subs.map { s -> s.rolls }.toMutableList()
            var e = (item.enhance / 3 + 1) * 3
            while (e <= 15) {
                if (item.rank == "Heroic" && e == 12 && types.size < 4) {
                    val t = pool.filter { p -> p !in types }.random(rnd)
                    val (lo, hi) = roll.getValue(t)
                    types += t
                    vals += rnd.nextInt(lo, hi + 1).toDouble()
                    rolls += 1
                } else {
                    val i = rnd.nextInt(types.size)
                    val (lo, hi) = roll.getValue(types[i])
                    vals[i] += rnd.nextInt(lo, hi + 1)
                    rolls[i] += 1
                }
                e += 3
            }
            Item(
                item.id,
                item.slot,
                item.set,
                item.rank,
                item.level,
                15,
                item.mainType,
                main,
                types.indices.map { i -> Substat(types[i], vals[i], rolls[i], false) },
                false,
                null,
            )
        }
    }
}
