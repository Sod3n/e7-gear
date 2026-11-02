package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Counts changes to anything a computed result depends on; freshness keys read [value]. */
class Revision(private val changed: () -> Unit = {}) {
    var value by mutableIntStateOf(0)
        private set

    fun bump() {
        value++
        changed()
    }
}
