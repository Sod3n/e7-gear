package e7.gearscan.features.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.data.KnownIssues
import e7.gearscan.data.Reports
import e7.gearscan.state.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val CRASH_FILE = "crash.txt"

class FeedbackFeature(ctx: FeatureContext, private val content: ContentFeature) : Feature(ctx, "feedback") {
    var lastError: String? by mutableStateOf(null)

    var lastCrash: String? by mutableStateOf(null)
        private set

    var knownIssues by mutableStateOf(KnownIssues.cached(ctx.disk.dir))
        private set

    val logFile = savedFile("scan.log", backup = false, path = "scan.log")

    private val reportsDir get() = ctx.disk.file("reports")

    fun failed(label: String, e: Throwable) {
        android.util.Log.e("E7Work", "$label failed", e)
        lastError = "$label: ${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
    }

    suspend fun loadCrash() {
        lastCrash = withContext(Dispatchers.IO) { ctx.disk.file(CRASH_FILE).takeIf { it.exists() }?.readText() }
    }

    fun dismissCrash() {
        lastCrash = null
        ctx.disk.file(CRASH_FILE).delete()
    }

    fun saveScanLog(text: String) = logFile.write(text)

    fun scanLog(live: String): String = live.ifEmpty { logFile.file.takeIf { it.exists() }?.readText().orEmpty() }

    fun deviceInfo(): String {
        val v = runCatching { ctx.platform.appVersion }.getOrNull() ?: "?"
        return "App $v · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}" +
            (lastError?.let { " · last error: $it" } ?: "")
    }

    fun refreshKnownIssues() {
        val url = KnownIssues.url(content.content.feedbackUrl) ?: return
        ctx.scope.launch {
            withContext(Dispatchers.IO) { KnownIssues.fetch(ctx.http, url, ctx.disk.dir) }?.let { knownIssues = it }
        }
    }

    fun send(
        report: JSONObject,
        done: (Reports.Outcome) -> Unit,
    ) {
        ctx.scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    Reports.enqueue(reportsDir, report)
                    Reports.flush(ctx.http, reportsDir, content.content.feedbackUrl, report.getString("id"))
                }
            done(r)
        }
    }

    suspend fun flushQueued() {
        withContext(Dispatchers.IO) { Reports.flush(ctx.http, reportsDir, content.content.feedbackUrl) }
    }
}
