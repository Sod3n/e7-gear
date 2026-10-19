package e7.gearscan.opt

import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Mod
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat

/**
 * Substat modification (the game's Modification Stones): one substat of a +15 item becomes another stat, valued
 * from the stone grade and how often the replaced substat rolled. Rules and values follow the Fribbels optimizer.
 */
object Mods {
    val QUALITY_LABELS = listOf("Low roll", "Typical", "High roll")

    // [roll count 1..6] -> min to max
    private val REFORGED_LESSER = mapOf(
        GearStat.ATK to listOf(39 to 51, 64 to 87, 91 to 119, 111 to 137, 135 to 165, 175 to 194),
        GearStat.ATK_P to listOf(4 to 6, 8 to 11, 12 to 15, 16 to 19, 20 to 22, 22 to 24),
        GearStat.HP to listOf(190 to 228, 312 to 397, 442 to 527, 545 to 616, 650 to 771, 813 to 896),
        GearStat.HP_P to listOf(4 to 6, 8 to 11, 12 to 15, 16 to 19, 20 to 22, 22 to 24),
        GearStat.DEF to listOf(33 to 39, 45 to 70, 70 to 92, 87 to 104, 107 to 127, 134 to 148),
        GearStat.DEF_P to listOf(4 to 6, 8 to 11, 12 to 15, 16 to 19, 20 to 22, 22 to 24),
        GearStat.SPD to listOf(2 to 3, 3 to 5, 5 to 7, 7 to 9, 9 to 11, 10 to 12),
        GearStat.CR to listOf(3 to 4, 4 to 6, 6 to 8, 8 to 11, 11 to 13, 13 to 15),
        GearStat.CD to listOf(4 to 5, 6 to 8, 9 to 12, 13 to 16, 17 to 19, 19 to 21),
        GearStat.EFF to listOf(4 to 6, 8 to 11, 12 to 15, 16 to 19, 20 to 22, 22 to 24),
        GearStat.RES to listOf(4 to 6, 8 to 11, 12 to 15, 16 to 19, 20 to 22, 22 to 24),
    )
    private val REFORGED_GREATER = mapOf(
        GearStat.ATK to listOf(44 to 58, 72 to 99, 101 to 134, 123 to 153, 149 to 184, 194 to 217),
        GearStat.ATK_P to listOf(5 to 9, 10 to 14, 14 to 18, 18 to 22, 22 to 25, 24 to 27),
        GearStat.HP to listOf(214 to 259, 347 to 448, 490 to 590, 602 to 685, 715 to 858, 897 to 995),
        GearStat.HP_P to listOf(5 to 9, 10 to 14, 14 to 18, 18 to 22, 22 to 25, 24 to 27),
        GearStat.DEF to listOf(37 to 44, 50 to 79, 78 to 103, 96 to 116, 118 to 142, 148 to 165),
        GearStat.DEF_P to listOf(5 to 9, 10 to 14, 14 to 18, 18 to 22, 22 to 25, 24 to 27),
        GearStat.SPD to listOf(2 to 4, 4 to 6, 6 to 8, 8 to 11, 10 to 13, 11 to 14),
        GearStat.CR to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 14 to 16, 16 to 18),
        GearStat.CD to listOf(5 to 8, 8 to 11, 11 to 15, 15 to 19, 19 to 22, 21 to 24),
        GearStat.EFF to listOf(5 to 9, 10 to 14, 14 to 18, 18 to 22, 22 to 25, 24 to 27),
        GearStat.RES to listOf(5 to 9, 10 to 14, 14 to 18, 18 to 22, 22 to 25, 24 to 27),
    )
    private val PLAIN_LESSER = mapOf(
        GearStat.ATK to listOf(28 to 40, 42 to 65, 58 to 86, 67 to 93, 80 to 110, 109 to 128),
        GearStat.ATK_P to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 13 to 15, 14 to 16),
        GearStat.HP to listOf(134 to 172, 200 to 285, 274 to 359, 321 to 392, 370 to 491, 477 to 560),
        GearStat.HP_P to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 13 to 15, 14 to 16),
        GearStat.DEF to listOf(24 to 30, 27 to 52, 43 to 65, 51 to 68, 62 to 82, 80 to 94),
        GearStat.DEF_P to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 13 to 15, 14 to 16),
        GearStat.SPD to listOf(2 to 3, 2 to 4, 3 to 5, 4 to 6, 5 to 7, 6 to 8),
        GearStat.CR to listOf(2 to 3, 2 to 4, 3 to 5, 4 to 7, 6 to 8, 7 to 9),
        GearStat.CD to listOf(3 to 4, 4 to 6, 6 to 9, 9 to 12, 11 to 13, 12 to 14),
        GearStat.EFF to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 13 to 15, 14 to 16),
        GearStat.RES to listOf(3 to 5, 5 to 8, 8 to 11, 11 to 14, 13 to 15, 14 to 16),
    )
    private val PLAIN_GREATER = mapOf(
        GearStat.ATK to listOf(33 to 47, 50 to 77, 68 to 101, 79 to 109, 94 to 129, 128 to 151),
        GearStat.ATK_P to listOf(4 to 8, 7 to 11, 10 to 14, 13 to 17, 15 to 18, 16 to 19),
        GearStat.HP to listOf(158 to 203, 235 to 336, 322 to 422, 378 to 461, 435 to 578, 561 to 659),
        GearStat.HP_P to listOf(4 to 8, 7 to 11, 10 to 14, 13 to 17, 15 to 18, 16 to 19),
        GearStat.DEF to listOf(28 to 35, 32 to 61, 51 to 76, 60 to 80, 73 to 97, 94 to 111),
        GearStat.DEF_P to listOf(4 to 8, 7 to 11, 10 to 14, 13 to 17, 15 to 18, 16 to 19),
        GearStat.SPD to listOf(2 to 4, 3 to 5, 4 to 6, 5 to 8, 6 to 9, 7 to 10),
        GearStat.CR to listOf(2 to 4, 3 to 6, 5 to 8, 7 to 10, 9 to 11, 10 to 12),
        GearStat.CD to listOf(4 to 7, 6 to 9, 8 to 12, 11 to 15, 13 to 16, 14 to 17),
        GearStat.EFF to listOf(4 to 8, 7 to 11, 10 to 14, 13 to 17, 15 to 18, 16 to 19),
        GearStat.RES to listOf(4 to 8, 7 to 11, 10 to 14, 13 to 17, 15 to 18, 16 to 19),
    )

    fun value(to: GearStat, rolls: Int, greater: Boolean, reforgedTable: Boolean, quality: Double): Double {
        val t = when { reforgedTable && greater -> REFORGED_GREATER; reforgedTable -> REFORGED_LESSER; greater -> PLAIN_GREATER; else -> PLAIN_LESSER }
        val (lo, hi) = t.getValue(to)[(rolls - 1).coerceIn(0, 5)]
        return Math.round(lo + (hi - lo) * quality).toDouble()
    }

    /**
     * Every single-substat mod of [item] that turns a substat in [replace] (rolled at most [maxRolls] times, or the one
     * already modded) into a stat in [wanted]. [reforged]: the search counts level 85 gear as reforged.
     */
    /** The piece as it looks with [mod] applied: the same variant [variants] made when the build was found. */
    fun apply(item: Item, mod: Mod): Item {
        val subs = item.subs.toMutableList().also { s -> s.getOrNull(mod.index)?.let { s[mod.index] = Substat(mod.to, mod.toValue, it.rolls, true) } }
        return Item(item.id, item.slot, item.set, item.rank, item.level, item.enhance, item.mainType, item.mainValue, subs, item.locked, item.equippedOn, mod = mod)
    }

    fun variants(item: Item, wanted: Set<GearStat>, replace: (GearStat) -> Boolean, maxRolls: Int, greater: Boolean,
                 quality: Double, reforged: Boolean): List<Item> {
        if (item.enhance != 15 || item.ghost) return emptyList()
        val modded = item.subs.indexOfFirst { it.modified }
        val table = (reforged && item.reforgeable) || item.level >= 90
        val out = ArrayList<Item>()
        for ((i, s) in item.subs.withIndex()) {
            if (modded >= 0 && i != modded) continue
            if (s.rolls > maxRolls || !(s.modified || replace(s.type))) continue
            for (to in wanted) {
                if (to == item.mainType) continue
                if (item.slot == Slot.WEAPON && to.stat == e7.gearscan.data.Stat.DEF) continue
                if (item.slot == Slot.ARMOR && to.stat == e7.gearscan.data.Stat.ATK) continue
                if (to != s.type && item.subs.any { it.type == to }) continue
                val v = value(to, s.rolls, greater, table, quality)
                // re-rolling into the same stat is only worth a stone if even a low roll beats what is there
                if (to == s.type && value(to, s.rolls, greater, table, 0.0) <= s.value) continue
                val subs = item.subs.toMutableList().also { it[i] = Substat(to, v, s.rolls, true) }
                out += Item(item.id, item.slot, item.set, item.rank, item.level, item.enhance, item.mainType, item.mainValue, subs,
                    item.locked, item.equippedOn, mod = Mod(i, s.type, s.value, to, v))
            }
        }
        return out
    }
}
