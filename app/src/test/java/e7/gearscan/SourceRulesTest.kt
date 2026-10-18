package e7.gearscan

import e7.gearscan.data.Content
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Whole-app rules that single screens can't show: locale-proof formatting and every referenced icon on disk. */
class SourceRulesTest {
    private val src = File("src/main/java")
    private val assets = File("src/main/assets")

    @Test
    fun numberFormattingIgnoresPhoneLanguage() {
        // "%,d" on a Russian phone gives "2 466"; "%.1f" gives "4,4"
        val bad = Regex(""""[^"\n]*%[^"\n]*"\.format\((?!java\.util\.Locale|Locale)|String\.format\((?!java\.util\.Locale|Locale)|SimpleDateFormat\("[^"]*"\)""")
        val hits = src.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("locale-dependent formatting:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun oneLineTextsSayTheyAreCut() {
        // a maxLines = 1 Text with no overflow cuts long names silently; use overflow = Ellipsis, or FitText for numbers
        val hits = File(src, "e7/gearscan").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { "maxLines = 1" in it.value && "overflow" !in it.value && "softWrap = false" !in it.value && "fun FitText" !in it.value }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("one-line texts without an ellipsis:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun gridsGoThroughCardGrid() {
        // a hand-made grid lets one cell grow taller than its neighbour; CardGrid gives every row one height
        val hits = File(src, "e7/gearscan/ui").walk().filter { it.extension == "kt" && it.name != "Kit.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { ".chunked(" in it.value }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("grids built by hand (use CardGrid):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun fontsCoverEveryCharacterUsed() {
        // the bundled fonts are subsets; a character outside them renders as a box
        val fonts = File("src/main/res/font").listFiles()!!.filter { it.extension == "ttf" }.map { cmap(it.readBytes()) }
        val used = File(src, "e7/gearscan").walk().filter { it.extension == "kt" }
            .flatMap { f -> Regex("\"([^\"\\n]*)\"").findAll(f.readText()).map { it.groupValues[1] } }
            .flatMap { it.asSequence() }.filter { it.code > 0x7E && !it.isWhitespace() }.toSet()
        val missing = used.filter { c -> fonts.any { c.code !in it } }
        assertTrue("characters missing from a bundled font: " + missing.joinToString(" ") { "$it (U+%04X)".format(java.util.Locale.US, it.code) }, missing.isEmpty())
    }

    /** Code points of a TrueType font's format-4 (BMP) cmap subtable. */
    private fun cmap(b: ByteArray): Set<Int> {
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16 or u16(o + 2).toLong()).toInt()
        val table = (0 until u16(4)).map { 12 + 16 * it }.first { String(b, it, 4) == "cmap" }.let { u32(it + 8) }
        val sub = (0 until u16(table + 2)).map { table + 4 + 8 * it }.map { table + u32(it + 4) }.first { u16(it) == 4 }
        val segs = u16(sub + 6) / 2
        val out = HashSet<Int>()
        for (i in 0 until segs) {
            val end = u16(sub + 14 + 2 * i)
            val start = u16(sub + 16 + 2 * segs + 2 * i)
            val delta = u16(sub + 16 + 4 * segs + 2 * i)
            val roOff = sub + 16 + 6 * segs + 2 * i
            val ro = u16(roOff)
            for (c in start..minOf(end, 0xFFFE)) {
                val g = if (ro == 0) (c + delta) and 0xFFFF else u16(roOff + ro + 2 * (c - start)).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                if (g != 0) out += c
            }
        }
        return out
    }

    /** Game data and images ship from the content site only: the APK and the repo carry none. */
    @Test
    fun noGameImagesInTheApp() {
        val bundled = listOf("src/main/assets/icons", "src/main/assets/artifacts", "src/main/res/drawable-nodpi", "src/main/assets/content.json").filter { File(it).exists() } +
            File("src/main/res").walk().filter { it.name.startsWith("gear_") }.map { it.path }.toList()
        assertTrue("game data bundled in the app (it belongs on the content site, see tools/publish_content.sh):\n" + bundled.joinToString("\n"), bundled.isEmpty())
    }

    /** Every icon the app draws by name is in the image store the content site is built from. */
    @Test
    fun everyIconIsInTheImageStore() {
        // the image store sits next to the repo (or E7_IMAGES); machines without it skip this check
        val store = File(System.getenv("E7_IMAGES") ?: "../../e7-app-images/img")
        org.junit.Assume.assumeTrue("image store not on this machine", store.exists())
        val c = TestContent.content
        val wanted = c.sets.map { "icons/sets/${it.icon}.png" } +
            c.heroes.values.mapNotNull { it.element }.toSet().map { "icons/elements/element-$it.png" } +
            c.heroes.values.mapNotNull { it.role }.toSet().map { "icons/classes/class-$it.png" } +
            Stat.entries.map { "icons/stats/${it.icon}.png" } + Slot.entries.map { "gear/gear_${it.icon}.png" }
        val missing = wanted.filterNot { File(store, it).exists() }
        assertTrue("missing icons:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
