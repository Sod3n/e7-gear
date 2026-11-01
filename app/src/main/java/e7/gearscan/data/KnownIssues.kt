package e7.gearscan.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection

data class KnownIssue(val id: String, val title: String, val detail: String, val status: String, val fixedOn: String?) {
    val label: String get() = KnownIssues.LABELS.getValue(status) + if (status == "fixed" && fixedOn != null) " · $fixedOn" else ""
}

/** Problems we already know about, served by the same gateway that takes reports; shown so players needn't report them. */
object KnownIssues {
    val LABELS = mapOf("known" to "Known", "working" to "Working on it", "fixed" to "Fixed")
    private const val REPORTS_PATH = "/api/app-feedback"
    private const val CACHE = "known_issues.json"

    fun url(feedbackUrl: String?): String? =
        feedbackUrl?.takeIf { it.endsWith(REPORTS_PATH) }?.let { it.removeSuffix(REPORTS_PATH) + "/api/known-issues?app=gear" }

    /** null when [text] isn't a known-issues reply at all; entries that don't fit are skipped. */
    fun parse(text: String): List<KnownIssue>? {
        val arr = runCatching { JSONObject(text).getJSONArray("issues") }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val status = o.optString("status")
            if (title.isEmpty() || status !in LABELS) null
            else KnownIssue(o.optString("id"), title, o.optStringOrNull("detail")?.trim().orEmpty(), status, o.optStringOrNull("fixed_on"))
        }
    }

    fun cached(dir: File): List<KnownIssue> = runCatching { parse(File(dir, CACHE).readText()) }.getOrNull().orEmpty()

    /** Fresh list, also saved for the next time the phone is offline; null when it couldn't be fetched. */
    fun fetch(url: String, dir: File): List<KnownIssue>? = runCatching {
        val c = Http.open(url) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) return@runCatching null
            val text = c.inputStream.bufferedReader().use { it.readText() }
            parse(text)?.also { File(dir, CACHE).writeText(text) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
