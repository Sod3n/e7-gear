package e7.gearscan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Feature logic may use only features in lower layers (docs/REFACTOR_PLAN_2.md); screens may read any feature. */
class FeatureLayersTest {
    private val features = File("src/main/java/e7/gearscan/features")

    private val layer = mapOf(
        "settings" to 1, "update" to 1, "content" to 2, "feedback" to 3, "scan" to 4, "marks" to 5, "records" to 5,
        "heroes" to 6, "history" to 7, "optimize" to 8, "pool" to 9,
        "sellreview" to 10, "improveheroes" to 10, "findgear" to 10, "tracker" to 11,
    )

    private val packageOf = mapOf(
        "settings" to "settings", "appUpdate" to "update", "content" to "content", "feedback" to "feedback", "scan" to "scan",
        "marks" to "marks", "records" to "records", "heroes" to "heroes", "history" to "history", "optimize" to "optimize",
        "pool" to "pool", "sellReview" to "sellreview", "improveHeroes" to "improveheroes", "findGear" to "findgear",
        "tracker" to "tracker", "checklist" to "tracker", "overGame" to "tracker",
    )

    // Wrong-way connections not yet removed. Plan 2 empties this list; a fixed one must leave it.
    private val notYetFixed = setOf(
        "feedback->scan",
        "heroes->pool", "history->optimize", "history->pool",
        "sellreview->tracker", "improveheroes->tracker", "findgear->tracker",
    )

    private fun wrongWay(): Map<String, List<String>> {
        val use = Regex("""\b(?:g|graph)\.(\w+)""")
        val found = mutableMapOf<String, MutableList<String>>()
        features.listFiles()!!.filter { it.isDirectory }.forEach { dir ->
            val from = dir.name
            val own = layer[from] ?: error("features/$from has no layer in FeatureLayersTest")
            dir.walk().filter { it.extension == "kt" }.filter { "@Composable" !in it.readText() }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    use.findAll(line).forEach { m ->
                        val to = packageOf[m.groupValues[1]] ?: return@forEach
                        if (to != from && layer.getValue(to) >= own) found.getOrPut("$from->$to") { mutableListOf() } += "${f.name}:${i + 1}"
                    }
                }
            }
        }
        return found
    }

    @Test
    fun featureLogicUsesOnlyLowerLayers() {
        val found = wrongWay()
        val added = found.filterKeys { it !in notYetFixed }
        assertTrue("new wrong-way connections:\n" + added.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" }, added.isEmpty())
        val fixed = notYetFixed - found.keys
        assertTrue("fixed, remove from notYetFixed: $fixed", fixed.isEmpty())
    }
}
