package e7.gearscan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Light and dark come from the palettes in ui/Theme.kt; a colour written anywhere else would not switch. */
class ThemeRulesTest {
    private val src = File("src/main/java")
    private fun sources() = sequenceOf(src, File("../core/src/main/java")).flatMap { it.walk() }
    private fun hits(rule: Regex, skip: Set<String> = emptySet()) = sources().filter { it.extension == "kt" && it.name !in skip }.flatMap { f ->
        f.readLines().withIndex().filter { rule.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
    }.toList()

    @Test
    fun coloursComeFromThePalettes() {
        val raw = hits(Regex("""\bColor\(0x|\bColor\.(White|Black|Red|Green|Blue|Yellow|Gray|LightGray|DarkGray|Cyan|Magenta)\b"""), setOf("Theme.kt"))
        assertTrue("raw colours outside ui/Theme.kt (add a palette role instead):\n" + raw.joinToString("\n"), raw.isEmpty())
    }

    @Test
    fun paletteReadsHappenWhenDrawn() {
        // a top-level val evaluates once, so it would keep the theme the app started with
        val cached = hits(Regex("""^val\s+\w+\s*(:\s*[\w.<>]+\s*)?=\s*.*(\bC\.|Brush\.|ColorPainter\()"""))
        assertTrue("palette colours cached at startup (use `get() =`):\n" + cached.joinToString("\n"), cached.isEmpty())
    }
}
