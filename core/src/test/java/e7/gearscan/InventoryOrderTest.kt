package e7.gearscan

import e7.gearscan.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class InventoryOrderTest {
    private fun raw(
        id: Long,
        code: String = "test",
        exp: Int = 0,
        rank: String = "Epic",
        slot: String = "Weapon",
        wearing: String = "None",
        base: Double = 100.0,
        speed: Double = 10.0,
    ): JSONObject =
        JSONObject()
            .put(
                "id",
                id,
            ).put("code", code)
            .put("e", exp)
            .put("rank", rank)
            .put("gear", slot)
            .put("set", "SpeedSet")
            .put("level", 85)
            .put("enhance", 0)
            .put(
                "main",
                JSONObject().put("type", "Attack").put("value", 500),
            ).put("op", JSONArray().put(JSONArray().put("att").put(base)).put(JSONArray().put("speed").put(speed)))
            .put("substats", JSONArray().put(JSONObject().put("type", "Speed").put("value", speed)))
            .put("ingameEquippedId", wearing)

    private fun box(vararg items: JSONObject) = Box(JSONObject().put("items", JSONArray(items.toList())))

    @Test fun currentMainAndGameFlatWeightsAreUsed() {
        val raw = raw(1)
        val b = box(raw)
        assertEquals(24, InventoryOrder.score(b.items[0], raw)) // 100*.0498 + 10*2, not normalized +15 main
        val percent =
            raw(2)
                .put("main", JSONObject().put("type", "HealthPercent").put("value", 60))
                .put("op", JSONArray().put(JSONArray().put("max_hp_rate").put(.12)).put(JSONArray().put("def").put(30.0)))
        assertEquals(8, InventoryOrder.score(box(percent).items[0], percent)) // 12*.4 + 30*.135
    }

    @Test fun aBatchWalksSetThenSlotTabThenLevelThenTheEsGrid() {
        fun piece(
            id: Long,
            set: String,
            slot: String,
            level: Int,
            speed: Double = 10.0,
        ) = raw(id, slot = slot, speed = speed).put("set", set).put("level", level)
        val b =
            box(
                piece(1, "HitSet", "Ring", 85),
                piece(2, "SpeedSet", "Weapon", 88),
                piece(3, "HitSet", "Weapon", 85, speed = 13.0),
                piece(4, "SpeedSet", "Weapon", 85),
                piece(5, "HitSet", "Weapon", 85),
                piece(6, "HitSet", "Weapon", 88),
                piece(7, "HitSet", "Helmet", 85),
            )
        assertEquals(listOf(5L, 3L, 6L, 7L, 1L, 4L, 2L), InventoryOrder.walk(b.items, { it }, b::gameScore).map { it.id })
    }

    @Test fun personalScanMatchesTheGamesScores() {
        val fixture = System.getenv("E7_FIXTURES")?.let { File(it, "gear.txt") }
        val expected = System.getenv("E7_GAME_SORT_EXPECTED")?.let(::File)
        assumeTrue(fixture?.exists() == true && expected?.exists() == true)
        val b = Box.parse(fixture!!.readText())
        val items = b.json.getJSONArray("items")
        val raw = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.optLong("id", it.optLong("ingameId")) }
        val rows = JSONArray(expected!!.readText())
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val item = b.itemsById.getValue(r.getLong("id"))
            assertEquals("score for ${r.getLong("id")}", r.getInt("score"), InventoryOrder.score(item, raw[item.id]))
        }
    }
}
