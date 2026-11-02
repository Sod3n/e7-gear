package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.Screen
import e7.gearscan.Sheet

class Entry(val id: Long, val screen: Screen)

class Nav(start: Screen, private val dismissConfirm: () -> Boolean = { false }) {
    private var nextId = 1L
    private fun entry(s: Screen) = Entry(nextId++, s)

    var stack: List<Entry> by mutableStateOf(listOf(entry(start)))
        private set
    var sheet: Sheet? by mutableStateOf(null)

    private val sheetUnder = HashMap<Int, Pair<Screen, Sheet>>()

    val screen get() = stack.last().screen

    fun screens(): List<Screen> = stack.map { it.screen }

    fun setScreens(screens: List<Screen>) { stack = screens.map(::entry) }

    fun go(s: Screen) {
        sheet?.let { sheetUnder[stack.lastIndex] = screen to it }
        sheet = null
        stack = stack + entry(s)
    }

    fun replace(s: Screen) {
        sheet = null
        stack = stack.dropLast(1).ifEmpty { if (s == Screen.Optimize) emptyList() else listOf(entry(Screen.Optimize)) } + entry(s)
    }

    fun tab(s: Screen) {
        sheetUnder.clear()
        sheet = null
        stack = listOf(entry(s))
    }

    fun open(s: Sheet) { sheet = s }

    fun back(): Boolean {
        if (dismissConfirm()) return true
        if (sheet != null) { sheet = null; return true }
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        sheetUnder.remove(stack.lastIndex)?.takeIf { it.first == screen }?.let { sheet = it.second }
        return true
    }
}
