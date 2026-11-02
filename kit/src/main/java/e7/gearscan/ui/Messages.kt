package e7.gearscan.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Outcome {
    data object Done : Outcome
    data class Note(val text: String) : Outcome
    data class Failed(val text: String) : Outcome
}

class Toast(val text: String, val action: String?, val onAction: (() -> Unit)?) { val id = System.nanoTime() }

class Confirm(val title: String, val body: String, val action: String?, val run: (() -> Unit)?)

class Confirmed internal constructor()

class Messages {
    var toast: Toast? by mutableStateOf(null)
    var dialog: Confirm? by mutableStateOf(null)

    fun showToast(text: String, action: String? = null, onAction: (() -> Unit)? = null) { toast = Toast(text, action, onAction) }

    fun show(o: Outcome) {
        when (o) {
            Outcome.Done -> {}
            is Outcome.Note -> showToast(o.text)
            is Outcome.Failed -> showToast(o.text)
        }
    }

    fun explain(title: String, body: String) { dialog = Confirm(title, body, null, null) }

    fun confirm(title: String, body: String, action: String, onConfirmed: (Confirmed) -> Unit) {
        dialog = Confirm(title, body, action) { onConfirmed(Confirmed()) }
    }

    fun dismissConfirm(): Boolean {
        if (dialog == null) return false
        dialog = null
        return true
    }
}
