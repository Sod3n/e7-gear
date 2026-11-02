package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random

class EsForecastTest {
    private fun raw(enhance: Int): JSONObject {
        val events = mutableListOf("speed" to 3.0, "max_hp_rate" to .05, "cri" to .04, "res" to .04)
        listOf("speed" to 3.0, "cri" to .05, "speed" to 4.0, "res" to .04, "max_hp_rate" to .07).take(enhance / 3).forEach { events += it }
        val values = linkedMapOf<String, Double>()
        val counts = linkedMapOf<String, Int>()
        events.forEach { (s, v) ->
            values[s] = (values[s] ?: 0.0) + v
            counts[s] = (counts[s] ?: 0) + 1
        }
        val names = mapOf("speed" to GearStat.SPD, "max_hp_rate" to GearStat.HP_P, "cri" to GearStat.CR, "res" to GearStat.RES)
        return JSONObject()
            .put(
                "id",
                1,
            ).put("gear", "Helmet")
            .put("type", "helm")
            .put("set", "SpeedSet")
            .put("rank", "Epic")
            .put("level", 85)
            .put("enhance", enhance)
            .put("l", false)
            .put("code", "ecw6h")
            .put("mainStatId", "cra6_helm_m")
            .put("ingameEquippedId", "None")
            .put("main", JSONObject().put("type", "Health").put("value", if (enhance == 15) 2700 else 540))
            .put(
                "op",
                JSONArray().put(JSONArray().put("max_hp").put(540)).apply {
                    events.forEach { (s, v) ->
                        put(JSONArray().put(s).put(v))
                    }
                },
            ).put(
                "substats",
                JSONArray(
                    values.map { (s, v) ->
                        val t = names.getValue(s)
                        JSONObject()
                            .put(
                                "type",
                                t.key,
                            ).put("value", if (t.showsPercent) kotlin.math.round(v * 100) else v)
                            .put("rolls", counts.getValue(s))
                    },
                ),
            )
    }

    @Test fun finishedPieceIsTheGamesScore() {
        val r = raw(15)
        val item = Box.parseItem(r)!!
        val game = InventoryOrder.score(item, r)
        val es = EsForecast.of(item, game, game, reforge = false, reforgeAllowed = false, supported = true, budgetMs = 5000)
        assertEquals(game, es.typical!!.toInt())
        assertEquals(1.0, es.chance, 1e-12)
        assertEquals(0.0, EsForecast.of(item, game, game + 1, false, false, true, 5000).chance, 1e-12)
    }

    @Test fun chanceMatchesSimulatedFinishes() {
        val item = Box.parseItem(raw(3))!!
        val main = EsForecast.main15(item, false)
        val rnd = Random(7)
        val n = 200_000
        val totals =
            IntArray(n) {
                val subs = item.subs.associate { it.type to it.value }.toMutableMap()
                repeat(4) {
                    val t = item.subs[rnd.nextInt(4)].type
                    val r = Finish.range(t, 85)
                    subs[t] =
                        subs.getValue(t) + rnd.nextInt(r.first, r.last + 1)
                }
                floor(main + subs.entries.sumOf { (t, v) -> v * GearScore.weights.getValue(t) } + 1e-9).toInt()
            }
        for (bar in listOf(55, 60, 65, 70)) {
            val es = EsForecast.of(item, 0, bar, false, false, true, 5000)
            assertEquals("bar $bar", totals.count { it >= bar } / n.toDouble(), es.chance, 0.006)
        }
    }

    @Test fun finishCostFollowsTheGameTable() {
        val epic0 = Box.parseItem(raw(0))!!
        assertEquals(1.0, EsForecast.finishShare(epic0), 1e-12)
        val at12 = Item(1, Slot.HELMET, "SpeedSet", "Epic", 85, 12, GearStat.HP, 0.0, epic0.subs, false, null)
        assertEquals(81.5 / 156.25, EsForecast.finishShare(at12), 1e-12)
        assertEquals(0.0, EsForecast.finishShare(Box.parseItem(raw(15))!!), 0.0)
    }
}
