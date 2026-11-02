package e7.gearscan.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.ui.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

enum class Weight { Light, Heavy, Yielding }

interface ForegroundHost {
    fun shown(work: Work<*>?)
    fun progress(work: Work<*>, text: String, done: Int, total: Int) {}
}

object NoForeground : ForegroundHost {
    override fun shown(work: Work<*>?) {}
}

class WorkHost(
    internal val scope: CoroutineScope,
    private val foreground: ForegroundHost = NoForeground,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var active: List<Work<*>> by mutableStateOf(emptyList())
        private set
    var shown: Work<*>? = null
        private set
    private var lastProgressAt = 0L

    fun cancelAll() { active.toList().forEach { it.cancel() } }

    internal fun admit(work: Work<*>): Outcome? {
        val others = active.filter { it !== work }
        if (work.weight == Weight.Light) return null
        others.firstOrNull { it.weight == Weight.Heavy }?.let { return Outcome.Failed("Finish or stop ${it.name} first") }
        if (work.weight == Weight.Heavy) others.filter { it.weight == Weight.Yielding }.forEach { it.cancel() }
        return null
    }

    internal fun add(work: Work<*>) {
        if (work !in active) active = active + work
        refresh()
    }

    internal fun remove(work: Work<*>) {
        active = active - work
        refresh()
    }

    internal fun progressed(work: Work<*>, text: String?, done: Int, total: Int) {
        if (work !== shown) return
        val now = clock()
        if (now - lastProgressAt < PROGRESS_MS) return
        lastProgressAt = now
        foreground.progress(work, text ?: "$done of $total", done, total)
    }

    private fun refresh() {
        val next = active.firstOrNull { it.weight == Weight.Heavy } ?: active.firstOrNull { it.foreground }
        if (next !== shown) { shown = next; lastProgressAt = 0L; foreground.shown(next) }
    }

    private companion object { const val PROGRESS_MS = 1000L }
}

interface WorkScope : CoroutineScope {
    val resumeFrom: JSONObject?
    fun progress(done: Int, total: Int, text: String? = null)
    suspend fun checkpoint(state: JSONObject)
    fun onCancel(action: () -> Unit)
}

class Work<R>(
    val name: String,
    private val host: WorkHost,
    val weight: Weight,
    private val progress: SavedFile? = null,
    val foreground: Boolean = weight == Weight.Heavy,
    val notice: String = name,
) {
    var busy: Boolean by mutableStateOf(false)
        private set
    var fraction: Float? by mutableStateOf(null)
        private set
    var result: R? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var run: Run? = null

    private inner class Run : WorkScope {
        var job: Job? = null
        val cancelActions = CopyOnWriteArrayList<() -> Unit>()
        override val coroutineContext get() = host.scope.coroutineContext.minusKey(Job) + job!!
        override val resumeFrom: JSONObject? = progress?.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        override fun progress(done: Int, total: Int, text: String?) {
            if (run !== this || total <= 0) return
            fraction = (done.toFloat() / total).coerceIn(0f, 1f)
            host.progressed(this@Work, text, done, total)
        }
        override suspend fun checkpoint(state: JSONObject) { progress?.write(state.toString()) }
        override fun onCancel(action: () -> Unit) {
            cancelActions += action
            if (run !== this) action()
        }
    }

    fun start(block: suspend WorkScope.() -> R): Outcome {
        cancel()
        host.admit(this)?.let { return it }
        val r = Run()
        run = r
        busy = true
        fraction = null
        error = null
        host.add(this)
        r.job = host.scope.launch(start = CoroutineStart.LAZY) {
            try {
                val value = r.block()
                if (isActive && run === r) {
                    result = value
                    progress?.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (run === r) error = e.message ?: e.javaClass.simpleName
            } finally {
                if (run === r) finish()
            }
        }
        r.job?.start()
        return Outcome.Done
    }

    fun cancel() {
        val r = run ?: return
        run = null
        r.job?.cancel()
        r.cancelActions.forEach { it() }
        finish()
    }

    private fun finish() {
        run = null
        busy = false
        fraction = null
        host.remove(this)
    }
}

suspend fun <T> blocking(name: String, body: () -> T): T = suspendCancellableCoroutine { c ->
    thread(name = name) {
        try { c.resume(body()) } catch (e: Throwable) { c.resumeWithException(e) }
    }
}
