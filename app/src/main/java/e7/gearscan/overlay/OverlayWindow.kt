package e7.gearscan.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What only the overlay window knows: where it sits, how it is drawn and whether the app is in front. */
class OverlayWindow {
    var expanded by mutableStateOf(false)
    var usableHeightDp by mutableStateOf(0f)
    var tab by mutableStateOf(0)
    var opacity by mutableStateOf(0.8f)
    var running by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var captured by mutableStateOf(false)
    var closing by mutableStateOf(false)
    var bubbleLeft by mutableStateOf(false)
    var bubbleDrag: BubbleDrag? = null
    var bubbleOnScreen: android.graphics.Rect? = null

    var appInFront = false
        set(v) {
            field = v
            onAppInFront?.invoke(v)
        }
    var onAppInFront: ((Boolean) -> Unit)? = null

    var native by mutableStateOf(false)
    val listHidden get() = opacity == 0f && !native

    var floating by mutableStateOf(false)
    var floatingPanel by mutableStateOf(true)
}
