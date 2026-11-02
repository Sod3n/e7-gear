package e7.gearscan.features.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.KnownIssues
import e7.gearscan.data.Reports
import e7.gearscan.state.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val CRASH_FILE = "crash.txt"

class FeedbackFeature(g: AppGraph) : Feature(g, "feedback") {
    var lastError: String? by mutableStateOf(null)

    var lastCrash: String? by mutableStateOf(null)
        private set

    var knownIssues by mutableStateOf(KnownIssues.cached(g.disk.dir))
        private set

    val logFile = savedFile("scan.log", backup = false, path = "scan.log")

    private val reportsDir get() = g.disk.file("reports")

    fun failed(label: String, e: Throwable) {
        android.util.Log.e("E7Work", "$label failed", e)
        lastError = "$label: ${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
    }

    suspend fun loadCrash() {
        lastCrash = withContext(Dispatchers.IO) { g.disk.file(CRASH_FILE).takeIf { it.exists() }?.readText() }
    }

    fun dismissCrash() {
        lastCrash = null
        g.disk.file(CRASH_FILE).delete()
    }

    fun saveScanLog(text: String) = logFile.write(text)

    fun scanLog(live: String): String = live.ifEmpty { logFile.file.takeIf { it.exists() }?.readText().orEmpty() }

    fun deviceInfo(): String {
        val v = runCatching { g.platform.appVersion }.getOrNull() ?: "?"
        return "App $v · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}" +
            (lastError?.let { " · last error: $it" } ?: "")
    }

    fun refreshKnownIssues() {
        val url = KnownIssues.url(g.content.content.feedbackUrl) ?: return
        g.scope.launch {
            withContext(Dispatchers.IO) { KnownIssues.fetch(g.http, url, g.disk.dir) }?.let { knownIssues = it }
        }
    }

    fun send(
        report: JSONObject,
        done: (Reports.Outcome) -> Unit,
    ) {
        g.scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    Reports.enqueue(reportsDir, report)
                    Reports.flush(g.http, reportsDir, g.content.content.feedbackUrl, report.getString("id"))
                }
            done(r)
        }
    }

    suspend fun flushQueued() {
        withContext(Dispatchers.IO) { Reports.flush(g.http, reportsDir, g.content.content.feedbackUrl) }
    }
}
