package e7.gearscan.features.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.Content
import e7.gearscan.data.ContentUpdate
import e7.gearscan.data.Mirror
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.ui.Outcome
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContentFeature(g: AppGraph) : Feature(g, "content") {
    var checkedAt by saved("checkedAt", 0L, Codecs.long, keepOnErase = true)
    var mirror by saved("mirror", false, Codecs.bool, keepOnErase = true)
    val mirrorSwitch =
        object : Mirror.Switch {
            override var active: Boolean
                get() = mirror
                set(value) {
                    if (mirror != value) mirror = value
                }
        }
    val file = savedFile("content.json", backup = false, keepOnErase = true, path = "content.json")

    // the APK carries no game data: the last download, or nothing until the first one arrives
    var content: Content by mutableStateOf(load())
        private set

    var imageBase: String by mutableStateOf(content.imageBase)
        internal set

    var status: String? by mutableStateOf(null)
        private set

    internal var fetch: (String) -> ByteArray = ::download

    private fun load(): Content {
        val downloaded = runCatching { Content.parse(file.read()!!) }.getOrNull()
        return downloaded?.takeIf { it.schema <= ContentUpdate.SCHEMA } ?: Content.EMPTY
    }

    private fun download(url: String): ByteArray {
        val c = g.http.open(url) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.useCaches = false
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
        return c.inputStream.use { it.readBytes() }
    }

    /** [quiet] is the daily check, which says nothing unless it updates. */
    suspend fun check(quiet: Boolean = false): Outcome {
        val current = content
        if (!quiet) status = "Checking…"
        val out =
            withContext(Dispatchers.IO) {
                runCatching {
                    ContentUpdate.check(current) { site -> Mirror.fetch(site, mirrorSwitch, siteFirst = true) { url -> fetch(url) } }
                        // whole-file replace, so a write cut short never leaves half a content file
                        .also { o -> o.bytes?.let { b -> file.write(String(b, Charsets.UTF_8)) } }
                }
            }
        checkedAt = System.currentTimeMillis()
        val result =
            out.fold(
                { o ->
                    when (o.result) {
                        ContentUpdate.Result.UPDATED -> {
                            content = o.content!!
                            imageBase = content.imageBase
                            g.rev.bump()
                            Outcome.Note("Updated to ${o.version}")
                        }
                        ContentUpdate.Result.NEEDS_APP -> Outcome.Note("Content ${o.version} needs a newer app")
                        ContentUpdate.Result.CURRENT -> if (quiet) Outcome.Done else Outcome.Note("Up to date")
                    }
                },
                { Outcome.Failed("Couldn't check: ${it.message?.take(80)}") },
            )
        when {
            result is Outcome.Note -> status = result.text
            result is Outcome.Failed && !quiet -> status = result.text
        }
        g.store.saveNow()
        return result
    }

    fun checkNow(quiet: Boolean = false) {
        g.scope.launch { check(quiet) }
    }
}
