package e7.gearscan

import e7.gearscan.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class InventoryOrderTest {
    private fun raw(id: Long, code: String = "test", exp: Int = 0, rank: String = "Epic", slot: String = "Weapon", wearing: String = "None", base: Double = 100.0, speed: Double = 10.0): JSONObject = JSONObject()
        .put("id", id).put("code", code).put("e", exp).put("rank", rank).put("gear", slot).put("set", "SpeedSet").put("level", 85).put("enhance", 0)
        .put("main", JSONObject().put("type", "Attack").put("value", 500)).put("op", JSONArray().put(JSONArray().put("att").put(base)).put(JSONArray().put("speed").put(speed)))
        .put("substats", JSONArray().put(JSONObject().put("type", "Speed").put("value", speed))).put("ingameEquippedId", wearing)
    private fun box(vararg items: JSONObject) = Box(JSONObject().put("items", JSONArray(items.toList())))

    @Test fun currentMainAndGameFlatWeightsAreUsed() {
        val raw = raw(1)
        val b = box(raw)
        assertEquals(24, InventoryOrder.score(b.items[0], raw)) // 100*.0498 + 10*2, not normalized +15 main
        val percent = raw(2).put("main", JSONObject().put("type", "HealthPercent").put("value", 60))
            .put("op", JSONArray().put(JSONArray().put("max_hp_rate").put(.12)).put(JSONArray().put("def").put(30.0)))
        assertEquals(8, InventoryOrder.score(box(percent).items[0], percent)) // 12*.4 + 30*.135
    }
    @Test fun tiesAndAscendingReverseTheWholeList() {
        val b = box(raw(1, exp = 2), raw(3, exp = 1), raw(2, exp = 2), raw(4, code = "z"), raw(5, rank = "Heroic"))
        val high = InventoryOrder.index(b, ascending = false).entries.sortedBy { it.value.number }.map { it.key }
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), high)
        val low = InventoryOrder.index(b).entries.sortedBy { it.value.number }.map { it.key }
        assertEquals(high.reversed(), low)
    }
    @Test fun symbolsChaosAndSlotTiesMatchTheGame() {
        val b = box(raw(1, code = "normal", slot = "Helmet"), raw(2, code = "normal", slot = "Armor"), raw(3, code = "normal_chaos"), raw(4, code = "esh1_w"), raw(5, code = "ear1_w"))
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), InventoryOrder.index(b, ascending = false).entries.sortedBy { it.value.number }.map { it.key })
    }
    @Test fun bagClearedAndObservedChangesRenumberIndependentlyOfSearch() {
        val b = box(raw(1, speed = 5.0), raw(2, wearing = "123", speed = 10.0), raw(3, speed = 20.0))
        assertEquals(listOf(1L, 3L), InventoryOrder.index(b, bagOnly = true).keys.toList())
        assertEquals(1, InventoryOrder.index(b, removed = setOf(1)).getValue(2).number)
        val o = b.items[0]
        val improved = Item(o.id, o.slot, o.set, o.rank, o.level, 3, o.mainType, 160.0, listOf(Substat(GearStat.SPD, 30.0, 2, false)), false, null)
        assertEquals(3, InventoryOrder.index(b, observed = mapOf(1L to improved)).getValue(1).number)
        assertTrue(InventoryOrder.index(b, observed = mapOf(1L to improved)).getValue(1).estimated)
        assertEquals("Row 2 · column 1", InventoryOrder.Position(6, 30, 24).grid())
    }
    @Test fun personalScanMatchesOriginalArtifactOrderAndScores() {
        val fixture = System.getenv("E7_FIXTURES")?.let { File(it, "gear.txt") }
        val expected = System.getenv("E7_GAME_SORT_EXPECTED")?.let(::File)
        assumeTrue(fixture?.exists() == true && expected?.exists() == true)
        val b = Box.parse(fixture!!.readText())
        val rows = JSONArray(expected!!.readText())
        val index = InventoryOrder.index(b)
        assertEquals(rows.length(), index.size)
        File(expected.parentFile, "android-order.json").writeText(JSONArray(index.entries.sortedBy { it.value.number }.map { JSONObject().put("id", it.key).put("score", it.value.score) }).toString())
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val p = index.getValue(r.getLong("id"))
            assertEquals("position for ${r.getLong("id")}", i + 1, p.number)
            assertEquals("score for ${r.getLong("id")}", r.getInt("score"), p.score)
        }
    }
}
