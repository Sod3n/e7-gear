package e7.gearscan

import e7.gearscan.data.Content
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URL

/**
 * Game content for tests. The APK and the repo carry none, so tests read content/content.json at the repo root
 * (written by tools/publish_content.sh, kept out of git), downloading it from the content site once if it's missing.
 */
object TestContent {
    val file = File("../content/content.json")

    val text: String by lazy {
        if (!file.exists()) runCatching {
            val site = "https://e7-gear-content.pages.dev/"
            val manifest = JSONObject(URL(site + "manifest.json").readText())
            file.parentFile.mkdirs()
            file.writeText(URL(site + manifest.getString("file")).readText())
        }
        assumeTrue("no game content: run tools/publish_content.sh --dry-run or allow the download", file.exists())
        file.readText()
    }

    val content: Content by lazy { Content.parse(text) }
}
