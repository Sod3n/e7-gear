package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.ui.Outcome

class FreshWork<K, R>(name: String, host: WorkHost, weight: Weight, private val inputs: () -> K) {
    private class Published<K, R>(val key: K, val value: R)

    private val work = Work<Published<K, R>>(name, host, weight)

    val result: R? get() = work.result?.value
    val busy: Boolean get() = work.busy
    val error: String? get() = work.error
    val stale: Boolean get() = work.result?.let { it.key != inputs() } ?: false

    fun start(block: suspend WorkScope.(key: K) -> R): Outcome {
        val key = inputs()
        return work.start { Published(key, block(key)) }
    }

    fun cancel() = work.cancel()
}
