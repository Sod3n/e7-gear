package e7.gearscan.state

import e7.gearscan.Screen
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NavTest {
    private class Reference {
        var stack: List<Screen> = listOf(Screen.Optimize)
        var sheet: Sheet? = null
        var confirm = false
        private val sheetUnder = HashMap<Int, Pair<Screen, Sheet>>()
        fun go(s: Screen) { sheet?.let { sheetUnder[stack.lastIndex] = stack.last() to it }; sheet = null; stack = stack + s }
        fun replace(s: Screen) { sheet = null; stack = stack.dropLast(1).ifEmpty { if (s == Screen.Optimize) emptyList() else listOf(Screen.Optimize) } + s }
        fun tab(s: Screen) { sheetUnder.clear(); sheet = null; stack = listOf(s) }
        fun back(): Boolean {
            if (confirm) { confirm = false; return true }
            if (sheet != null) { sheet = null; return true }
            if (stack.size <= 1) return false
            stack = stack.dropLast(1)
            sheetUnder.remove(stack.lastIndex)?.takeIf { it.first == stack.last() }?.let { sheet = it.second }
            return true
        }
    }

    private val a = Sheet(SheetKind.TARGETS, 1)
    private val b = Sheet(SheetKind.SETS, 2)

    private fun check(ops: List<(Any) -> Any?>) {
        var confirm = false
        val ref = Reference()
        val nav = Nav(Screen.Optimize) { if (confirm) { confirm = false; true } else false }
        for (op in ops) {
            val r = op(ref)
            val n = op(object : Target(nav) { override fun setConfirm() { confirm = true } })
            assertEquals(r, n)
            assertEquals(ref.stack, nav.screens())
            assertEquals(ref.sheet, nav.sheet)
        }
    }

    private abstract class Target(val nav: Nav) { abstract fun setConfirm() }

    private fun go(s: Screen): (Any) -> Any? = { t -> if (t is Reference) t.go(s) else (t as Target).nav.go(s) }
    private fun replace(s: Screen): (Any) -> Any? = { t -> if (t is Reference) t.replace(s) else (t as Target).nav.replace(s) }
    private fun tab(s: Screen): (Any) -> Any? = { t -> if (t is Reference) t.tab(s) else (t as Target).nav.tab(s) }
    private fun open(s: Sheet?): (Any) -> Any? = { t -> if (t is Reference) t.sheet = s else (t as Target).nav.sheet = s }
    private val back: (Any) -> Any? = { t -> if (t is Reference) t.back() else (t as Target).nav.back() }
    private val confirm: (Any) -> Any? = { t -> if (t is Reference) t.confirm = true else (t as Target).setConfirm() }

    @Test fun sheetOpenUnderAScreenReturnsOnBack() = check(listOf(
        open(a), go(Screen.Gear), back, back, back, back,
    ))

    @Test fun nestedScreensRestoreTheirOwnSheets() = check(listOf(
        open(a), go(Screen.Heroes), open(b), go(Screen.Results), back, back, back, back, back,
    ))

    @Test fun sheetIsNotRestoredOverADifferentScreen() = check(listOf(
        open(a), go(Screen.Gear), replace(Screen.Heroes), back, back,
    ))

    @Test fun tabClearsRememberedSheets() = check(listOf(
        open(a), go(Screen.Gear), tab(Screen.Heroes), go(Screen.Gear), back, back, back,
    ))

    @Test fun replaceKeepsOptimizeUnderTheOnlyScreen() = check(listOf(
        replace(Screen.Gear), back, back, replace(Screen.Optimize), replace(Screen.Optimize), go(Screen.Cleanup), replace(Screen.Gear),
    ))

    @Test fun confirmIsDismissedBeforeSheetAndStack() = check(listOf(
        go(Screen.Gear), open(a), confirm, back, back, back, back,
    ))

    @Test fun backOnTheRootIsRefused() = check(listOf(back, tab(Screen.Heroes), back))

    @Test fun sameScreenTwiceGetsTwoEntries() {
        val nav = Nav(Screen.Optimize)
        nav.go(Screen.HeroUpgrades()); nav.go(Screen.HeroUpgrades())
        val ids = nav.stack.map { it.id }
        assertEquals(3, ids.toSet().size)
        nav.back()
        assertEquals(ids.take(2), nav.stack.map { it.id })
        nav.setScreens(listOf(Screen.Gear))
        assertNotEquals(ids[0], nav.stack[0].id)
    }
}
