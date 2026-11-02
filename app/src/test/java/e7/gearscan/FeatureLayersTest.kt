package e7.gearscan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Feature logic may use only features in lower layers (docs/REFACTOR_PLAN_2.md); screens may read any feature. */
class FeatureLayersTest {
    private val features = File("src/main/java/e7/gearscan/features")

    private val layerOf = mapOf(
        "SettingsFeature" to 1, "AppUpdateFeature" to 1, "ContentFeature" to 2, "FeedbackFeature" to 3, "ScanFeature" to 4,
        "MarksFeature" to 5, "RecordsFeature" to 5, "HeroesFeature" to 6, "HistoryFeature" to 7, "OptimizeFeature" to 8,
        "PoolFeature" to 9, "SellReviewFeature" to 10, "ImproveHeroesFeature" to 10, "FindGearFeature" to 10,
        "TrackerFeature" to 11, "ChecklistFeature" to 12,
    )

    private fun logicFiles() = features.walk().filter { it.extension == "kt" && "@Composable" !in it.readText() }.toList()

    @Test
    fun featureLogicDoesNotKnowTheGraph() {
        val found = logicFiles().filter { Regex("""\bAppGraph\b""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("feature logic must take what it uses, not the graph: $found", found.isEmpty())
    }

    @Test
    fun featuresTakeOnlyLowerLayers() {
        val header = Regex("""(?:^|\n)class (\w+)\(((?:(?!\nclass )[^{])*?)\)\s*:\s*Feature\(""")
        val seen = mutableSetOf<String>()
        val wrong = mutableListOf<String>()
        logicFiles().forEach { f ->
            header.findAll(f.readText()).forEach { m ->
                val name = m.groupValues[1]
                val own = layerOf[name] ?: error("$name has no layer in FeatureLayersTest")
                seen += name
                Regex(""":\s*(\w+Feature)\b""").findAll(m.groupValues[2]).forEach { p ->
                    val dep = p.groupValues[1]
                    val layer = layerOf[dep] ?: error("$name takes $dep, which has no layer in FeatureLayersTest")
                    if (layer >= own) wrong += "$name($own) -> $dep($layer)"
                }
            }
        }
        assertTrue("features not found: ${layerOf.keys - seen}", seen == layerOf.keys)
        assertTrue("upward or sideways constructor parameters: $wrong", wrong.isEmpty())
    }
}
