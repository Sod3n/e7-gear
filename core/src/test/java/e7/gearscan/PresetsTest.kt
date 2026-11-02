package e7.gearscan

import e7.gearscan.data.Content
import e7.gearscan.data.Presets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PresetsTest {
    private val content = TestContent.content

    private fun rta(vararg sets: Pair<String, Int>) = JSONObject("""{"0": {"a": {"builds": [{"sample": 6, "artifact": "efa08", "weight": 0.125,
        "sets": [${sets.joinToString(",") { """{"name": "${it.first}", "pieces": ${it.second}}""" }}],
        "stats": {"att": 3421, "max_hp": 8854, "def": 910, "speed": 320, "cri": 1.05, "cri_dmg": 1.95, "acc": 0.06, "res": 0.17}}]}}}""")

    @Test
    fun rtaSamplesKeepOnlyCompleteSets() {
        // Rhianna and Luciella's RTA #6: one Unity and one Reversal piece are not sets
        val p = Presets.parseRta(rta("Unity" to 1, "Reversal" to 1, "Speed" to 4), content).single()
        assertEquals(mapOf("SpeedSet" to 4), p.sets)
        // five speed pieces still make one speed set
        assertEquals(mapOf("SpeedSet" to 4), Presets.parseRta(rta("Critical" to 1, "Speed" to 5), content).single().sets)
        assertEquals(105.0, p.median[4], 1e-9)
    }

    @Test
    fun realHelperFileParses() {
        // Frieren's file as the helper serves it (2026-10-18)
        val j = JSONObject(File("src/test/resources/rta_c1172.json").readText())
        val p = Presets.parseRta(j, content)
        assertEquals(8, p.size)
    }
}
