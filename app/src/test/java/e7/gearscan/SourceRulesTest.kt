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
    private fun sources() = sequenceOf(src, File("../core/src/main/java"), File("../kit/src/main/java")).flatMap { it.walk() }
    private val assets = File("src/main/assets")

    @Test
    fun upgradeTrackerHasNoScrollContainers() {
        val overlay = File(src, "e7/gearscan/overlay/UpgradeOverlay.kt").readText()
        val forbidden = listOf("verticalScroll(", "horizontalScroll(", "LazyColumn(", "LazyRow(", ".scrollable(")
        assertTrue("The gear overlay must use fitted pages, never scrolling", forbidden.none { it in overlay })
    }

    @Test
    fun appHasNoRawTextFields() {
        val forbidden = Regex("""\b(?:BasicTextField|TextField|OutlinedTextField|EditText|AutoCompleteTextView)\b""")
        val hits = src.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" }
        }.toList()
        assertTrue("Keyboard input goes through the kit's @KeyboardInput fields only: " + hits.joinToString(), hits.isEmpty())
    }

    @Test
    fun numberFormattingIgnoresPhoneLanguage() {
        // "%,d" on a Russian phone gives "2 466"; "%.1f" gives "4,4"
        val bad = Regex(""""[^"\n]*%[^"\n]*"\.format\((?!java\.util\.Locale|Locale)|String\.format\((?!java\.util\.Locale|Locale)|SimpleDateFormat\("[^"]*"\)""")
        val hits = sources().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("locale-dependent formatting:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun chancesReadAsPercents() {
        val bad = Regex(""""[^"\n]*\b1 in [\d$]""")
        val hits = sources().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("Chances are shown as percents through Chance, never \"1 in N\":\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun textsHoldNoCodeLeftByRenames() {
        // a rename that also hit a message ("Your g.scan.lastScan changed"), or a lost brace: "$t.worn.equippedOn" prints t itself
        val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        val code = Regex("""\b(?:g|graph|ctx)\.[a-z]|\$[a-z]\w*\.(?!(?:apk|png|json|none|webp|jpg|txt|v)\b)[a-z]""")
        val hits = sources().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().flatMap { (i, line) ->
                literal.findAll(line).map { it.groupValues[1].replace(Regex("""\$\{[^}]*}"""), "").replace(Regex("""\$\{.*"""), "") }
                    .filter { code.containsMatchIn(it) }.map { "${f.name}:${i + 1}: $it" }
            }
        }.toList()
        assertTrue("code inside texts:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun oneLineTextsSayTheyAreCut() {
        // a maxLines = 1 Text with no overflow cuts long names silently; use overflow = Ellipsis, or FitText for numbers
        val hits = sources().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { "maxLines = 1" in it.value && "overflow" !in it.value && "softWrap = false" !in it.value && "fun FitText" !in it.value }
                .map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("one-line texts without an ellipsis:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun requestsNameTheApp() {
        // Http.open adds "User-Agent: E7Gear/<version>"; a bare openConnection goes out as Android's default agent
        val hits = sources().filter { it.extension == "kt" && it.name != "Http.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { "openConnection(" in it.value }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("requests without the app's User-Agent (use Http.open):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun gridsGoThroughCardGrid() {
        // a hand-made grid lets one cell grow taller than its neighbour; CardGrid gives every row one height
        val hits = listOf("ui", "features").map { File(src, "e7/gearscan/$it") }.flatMap { it.walk() }.filter { it.extension == "kt" && it.name != "Kit.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { ".chunked(" in it.value }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertTrue("grids built by hand (use CardGrid):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun fontsCoverEveryCharacterUsed() {
        // the bundled fonts are subsets; a character outside them renders as a box
        val fonts = File("../kit/src/main/res/font").listFiles()!!.filter { it.extension == "ttf" }.map { cmap(it.readBytes()) }
        val used = sources().filter { it.extension == "kt" }
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
