package e7.gearscan.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Bug reports wait in [dir] until they reach the report endpoint. */
object Reports {
    enum class Outcome { SENT, QUEUED, NO_ENDPOINT }

    fun newId(): String = (0 until 4).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")

    fun enqueue(dir: File, report: JSONObject) {
        dir.mkdirs()
        File(dir, "${report.getString("id")}.json").writeText(report.toString())
    }

    /** Sends every queued report; true when [id] (if given) went out. */
    fun flush(dir: File, url: String?, id: String? = null): Outcome {
        if (url.isNullOrEmpty()) return Outcome.NO_ENDPOINT
        var mine = Outcome.QUEUED
        for (f in dir.listFiles { x -> x.name.endsWith(".json") }.orEmpty()) {
            val code = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(f.readBytes()) }
                c.responseCode
            }.getOrDefault(-1)
            when {
                code in 200..299 -> { if (f.name == "$id.json") mine = Outcome.SENT; f.delete() }
                // the server will never take this one (too long, bad topic); 429 and network errors retry later
                code in 400..499 && code != 429 -> f.delete()
            }
        }
        return mine
    }
}
