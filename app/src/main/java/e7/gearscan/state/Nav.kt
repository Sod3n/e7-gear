package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.Screen
import e7.gearscan.Sheet

class Entry(val id: Long, val screen: Screen)

class Nav(
    start: Screen,
    private val heroName: (Long) -> String? = { null },
    private val poolRunning: () -> Boolean = { false },
    private val dismissConfirm: () -> Boolean = { false },
) {
    private var nextId = 1L
    private fun entry(s: Screen) = Entry(nextId++, s)

    var entries: List<Entry> by mutableStateOf(listOf(entry(start)))
        private set
    var sheet: Sheet? by mutableStateOf(null)

    private val sheetUnder = HashMap<Int, Pair<Screen, Sheet>>()

    val screen get() = entries.last().screen

    var stack: List<Screen>
        get() = entries.map { it.screen }
        internal set(v) { entries = v.map(::entry) }

    /** A screen as a back link names it: its title. */
    fun label(s: Screen): String = when (s) {
        Screen.ScanIntro, Screen.Capture -> "Scan"
        Screen.ScanData -> "Scan & data"
        Screen.ScanResult -> "Imported"
        Screen.Optimize -> "Optimize"
        Screen.Gear -> "Gear"
        Screen.Heroes -> "Heroes"
        Screen.Results -> "Best 50"
        Screen.GhostBuild -> "Ghost build"
        is Screen.Compare -> s.poolHero?.let { heroName(it) } ?: "Build #${s.index + 1}"
        is Screen.HeroDetail -> heroName(s.id) ?: "Hero"
        is Screen.DiffItems -> listOf("New items", "Enhanced", "Moved").getOrElse(s.kind) { "Imported" }
        Screen.OverlayIntro -> "Show over game"
        is Screen.Feedback -> "Feedback"
        Screen.PoolRun -> if (poolRunning()) "Optimizing pool" else "Pool done"
        is Screen.PoolReview -> heroName(s.id) ?: "Pool done"
        is Screen.PoolHero -> heroName(s.id) ?: "Whole pool"
        Screen.Upgrade -> "Track upgrades"
        Screen.GearPotential -> "Find gear"
        Screen.SellFilters -> "Sell review"
        Screen.SellKept -> "Kept pieces"
        is Screen.HeroUpgrades -> s.heroId?.let { heroName(it) } ?: "Improve heroes"
    }

    /** Where back goes from the current screen, by name. */
    fun backLabel(): String = stack.getOrNull(stack.size - 2)?.let(::label) ?: "Back"

    fun go(s: Screen) {
        sheet?.let { sheetUnder[entries.lastIndex] = screen to it }
        sheet = null
        entries = entries + entry(s)
    }

    fun replace(s: Screen) {
        sheet = null
        entries = entries.dropLast(1).ifEmpty { if (s == Screen.Optimize) emptyList() else listOf(entry(Screen.Optimize)) } + entry(s)
    }

    fun tab(s: Screen) {
        sheetUnder.clear()
        sheet = null
        entries = listOf(entry(s))
    }

    fun open(s: Sheet) { sheet = s }

    fun back(): Boolean {
        if (dismissConfirm()) return true
        if (sheet != null) { sheet = null; return true }
        if (entries.size <= 1) return false
        entries = entries.dropLast(1)
        sheetUnder.remove(entries.lastIndex)?.takeIf { it.first == screen }?.let { sheet = it.second }
        return true
    }
}
