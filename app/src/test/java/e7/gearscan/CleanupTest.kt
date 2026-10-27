package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.Slot
import e7.gearscan.data.Substat
import e7.gearscan.opt.Cleanup
import e7.gearscan.opt.Finish
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CleanupTest {
    private var nextId = 1L

    private fun item(slot: Slot, set: String, enhance: Int, main: GearStat, vararg subs: Pair<GearStat, Double>, worn: Long? = null) =
        JSONObject().put("id", nextId++).put("gear", slot.key).put("set", set).put("rank", "Epic").put("level", 85).put("enhance", enhance)
            .put("main", JSONObject().put("type", main.key).put("value", if (main == GearStat.HP) 2700 else 60))
            .put("op", JSONArray().put(JSONArray().put(main.key).put(if (main == GearStat.HP) 540 else 0.12)))
            .put("substats", JSONArray(subs.map { (t, v) -> JSONObject().put("type", t.key).put("value", v).put("rolls", 1) }))
            .put("ingameEquippedId", worn?.toString() ?: "None")

    private fun box(vararg items: JSONObject) = Box(JSONObject().put("items", JSONArray(items.toList())).put("heroes", JSONArray()))

    @Test
    fun onlyExactDuplicatesOfFinishedPiecesGo() {
        val b = box(
            item(Slot.HELMET, "SpeedSet", 15, GearStat.HP, GearStat.SPD to 10.0, GearStat.CR to 8.0),
            item(Slot.HELMET, "SpeedSet", 15, GearStat.HP, GearStat.SPD to 10.0, GearStat.CR to 8.0),
            // better in speed, worse in crit: not a duplicate
            item(Slot.HELMET, "SpeedSet", 15, GearStat.HP, GearStat.SPD to 14.0, GearStat.CR to 4.0),
        )
        val d = Cleanup.duplicates(b, emptySet())
        assertEquals(1, d.size)
        assertTrue(d[0].twin != null && d[0].twin!!.id != d[0].item.id)
    }

    @Test
    fun aTicketGoesOnlyWhenTwoPiecesOfItsKindBeatItInEveryPair() {
        val b = box(
            item(Slot.HELMET, "SpeedSet", 15, GearStat.HP, GearStat.SPD to 18.0, GearStat.CR to 15.0, GearStat.CD to 20.0, GearStat.HP_P to 20.0),
            item(Slot.HELMET, "SpeedSet", 15, GearStat.HP, GearStat.SPD to 17.0, GearStat.CR to 14.0, GearStat.CD to 19.0, GearStat.HP_P to 19.0),
            item(Slot.HELMET, "SpeedSet", 0, GearStat.HP, GearStat.SPD to 3.0, GearStat.CR to 3.0, GearStat.CD to 4.0, GearStat.HP_P to 4.0),
            // a different set is a different kind: nothing covers it
            item(Slot.HELMET, "HitSet", 0, GearStat.HP, GearStat.SPD to 3.0, GearStat.CR to 3.0, GearStat.CD to 4.0, GearStat.HP_P to 4.0),
        )
        val s = Cleanup.stock(b, emptySet(), keep = 2)
        assertEquals(listOf(3L), s.map { it.item.id })
        assertEquals(2, s[0].better.size)
        assertTrue(Cleanup.stock(b, emptySet(), keep = 3).isEmpty())
    }

    @Test
    fun finishesReachPlus15WithTheRightRolls() {
        val it = Item(1, Slot.BOOTS, "SpeedSet", "Heroic", 85, 0, GearStat.SPD, 8.0,
            listOf(Substat(GearStat.CR, 4.0, 1, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 6.0, 1, false)), false, null, main15 = 40.0)
        for (f in Finish.of(it, 200, Random(3))) {
            assertEquals(15, f.enhance)
            assertEquals(40.0, f.mainValue, 0.0)
            assertEquals(4, f.subs.size)
            // Heroic: 3 lines + 3 upgrades, a 4th line at +12, one more upgrade at +15
            assertEquals(8, f.subs.sumOf { s -> s.rolls })
        }
    }
}
