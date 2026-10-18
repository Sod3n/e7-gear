package e7.gearscan

import e7.gearscan.data.Content
import e7.gearscan.data.ContentUpdate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The content site's answers against the bundled content: what the app takes, skips or refuses. */
class ContentUpdateTest {
    private val bundled = JSONObject(TestContent.text).put("contentManifest", "https://site.test/manifest.json")
    private val current = Content.parse(bundled.toString())

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(java.util.Locale.US, it) }

    /** A site serving [next] (as the manifest says, unless [sum] or [schema] say otherwise). */
    private fun site(next: JSONObject, schema: Int = 1, sum: String? = null): (String) -> ByteArray {
        val file = next.toString().toByteArray()
        val manifest = JSONObject().put("version", next.getString("version")).put("schema", schema).put("file", "content-x.json").put("sha256", sum ?: sha(file))
        return { url -> when (url) {
            "https://site.test/manifest.json" -> manifest.toString().toByteArray()
            "https://site.test/content-x.json" -> file
            else -> error("unexpected $url")
        } }
    }

    private fun newer() = JSONObject(bundled.toString()).put("version", current.version + "T23:59")

    @Test
    fun takesNewerContent() {
        val o = ContentUpdate.check(current, site(newer()))
        assertEquals(ContentUpdate.Result.UPDATED, o.result)
        assertEquals(current.heroes.size, o.content!!.heroes.size)
    }

    @Test
    fun skipsWhatItHas() = assertEquals(ContentUpdate.Result.CURRENT, ContentUpdate.check(current, site(JSONObject(bundled.toString()))).result)

    @Test
    fun leavesNewerSchemaToAnAppUpdate() = assertEquals(ContentUpdate.Result.NEEDS_APP, ContentUpdate.check(current, site(newer(), schema = 2)).result)

    @Test
    fun refusesBadChecksum() {
        assertTrue(runCatching { ContentUpdate.check(current, site(newer(), sum = "00")) }.isFailure)
    }

    @Test
    fun refusesContentThatLostHeroes() {
        val broken = newer().also { it.getJSONArray("heroes").remove(0) }
        assertTrue(runCatching { ContentUpdate.check(current, site(broken)) }.exceptionOrNull()?.message?.contains("fewer heroes") == true)
    }
}
