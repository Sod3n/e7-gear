package e7.gearscan.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

// Restored values are dropped when the data they were for changed while the screen was away.
@Composable
fun <T : Any> rememberSavedFor(key: Any?, initial: T): MutableState<T> {
    val hash = remember(key) { key.hashCode() }
    val state = rememberSaveable { mutableStateOf(initial) }
    var savedHash by rememberSaveable { mutableStateOf(hash) }
    if (savedHash != hash) { savedHash = hash; state.value = initial }
    return state
}
