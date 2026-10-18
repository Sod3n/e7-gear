package e7.gearscan

import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat
import e7.gearscan.opt.Mods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModsTest {
    private fun item(slot: Slot, enhance: Int, vararg subs: Substat, level: Int = 88) =
        Item(7, slot, "SpeedSet", "Epic", level, enhance, GearStat.ATK, 515.0, subs.toList(), false, null)

    private val wanted = setOf(GearStat.SPD, GearStat.CR, GearStat.DEF_P)
    private fun variants(it: Item, maxRolls: Int = 2) = Mods.variants(it, wanted, { t -> t !in wanted }, maxRolls, greater = true, quality = 0.5, reforged = true)

    @Test
    fun onlyPlusFifteenAndWeakUnwantedSubstats() {
        val subs = arrayOf(Substat(GearStat.HP, 180.0, 1, false), Substat(GearStat.ATK_P, 20.0, 4, false),
            Substat(GearStat.CR, 8.0, 2, false), Substat(GearStat.EFF, 6.0, 1, false))
        assertTrue(variants(item(Slot.WEAPON, 12, *subs)).isEmpty())
        val v = variants(item(Slot.WEAPON, 15, *subs))
        // HP (1 roll) and Eff (1 roll) can go; Atk% rolled 4 times stays; Cr is already there; a weapon never gets Def
        assertEquals(setOf(0 to GearStat.SPD, 3 to GearStat.SPD), v.map { it.mod!!.index to it.mod!!.to }.toSet())
        assertTrue(v.all { it.id == 7L })
    }

    @Test
    fun valueFromTheGameTable() {
        // greater stone, lv88 gear uses the unreforged table: speed after one roll is 2..4
        val v = variants(item(Slot.BOOTS, 15, Substat(GearStat.DEF, 30.0, 1, false))).first { it.mod!!.to == GearStat.SPD }
        assertEquals(3.0, v.subs[0].value, 0.0)
        // level 90 gear uses the reforged table: 2..4 too after one roll, 8..11 after four
        val w = variants(item(Slot.BOOTS, 15, Substat(GearStat.DEF, 30.0, 4, false), level = 90), maxRolls = 4).first { it.mod!!.to == GearStat.SPD }
        assertEquals(10.0, w.subs[0].value, 0.0)
    }

    @Test
    fun anAlreadyModdedSubstatIsTheOnlyOneThatCanChange() {
        val it = item(Slot.HELMET, 15, Substat(GearStat.HP, 180.0, 1, false), Substat(GearStat.ATK, 40.0, 1, true))
        assertEquals(setOf(1), variants(it).map { v -> v.mod!!.index }.toSet())
    }
}
