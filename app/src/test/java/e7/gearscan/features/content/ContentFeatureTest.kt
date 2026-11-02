package e7.gearscan.features.content

import e7.gearscan.AppGraph
import e7.gearscan.data.Content
import e7.gearscan.data.Mirror
import e7.gearscan.state.Disk
import e7.gearscan.ui.Outcome
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("content").toFile()) = AppGraph(Disk(dir), scope)

    private fun content(version: String, imageBase: String) = JSONObject()
        .put("version", version)
        .put("contentManifest", Mirror.SITE + "manifest.json")
        .put("images", JSONObject().put("face", "").put("artifact", "").put("base", imageBase))
        .put("builds", "")
        .put("sets", JSONArray())
        .put("heroes", JSONArray())
        .put("artifacts", JSONArray())

    private fun site(next: JSONObject): (String) -> ByteArray {
        val file = next.toString().toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(java.util.Locale.US, it) }
        val manifest = JSONObject().put("version", next.getString("version")).put("schema", 1).put("file", "content-next.json").put("sha256", sum)
        return { url ->
            when (url) {
                Mirror.SITE + "manifest.json" -> manifest.toString().toByteArray()
                Mirror.SITE + "content-next.json" -> file
                else -> throw IOException("unexpected $url")
            }
        }
    }

    @Test fun aFailedUserCheckIsAFailedOutcomeAndShowsInTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.content.fetch = { throw IOException("offline") }
        assertEquals(Outcome.Failed("Couldn't check: offline"), g.content.check())
        assertEquals("Couldn't check: offline", g.content.status)
        assertTrue(g.content.checkedAt > 0)
    }

    @Test fun aFailedQuietCheckLeavesTheStatusAlone() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.content.fetch = { throw IOException("offline") }
        assertTrue(g.content.check(quiet = true) is Outcome.Failed)
        assertNull(g.content.status)
    }

    @Test fun anUpdateReplacesTheContentTheImageBaseAndTheFile() = runTest(UnconfinedTestDispatcher()) {
        val dir = createTempDirectory("content").toFile()
        val g = graph(this, dir)
        assertEquals(Content.DEFAULT_IMAGE_BASE, g.content.imageBase)
        g.content.fetch = site(content("2", "https://images.test/img/"))
        assertEquals(Outcome.Note("Updated to 2"), g.content.check())
        assertEquals("2", g.content.content.version)
        assertEquals("https://images.test/img/", g.content.imageBase)
        assertEquals("Updated to 2", g.content.status)
        g.disk.flush()
        val again = graph(this, dir)
        assertEquals("2", again.content.content.version)
        assertEquals("https://images.test/img/", again.content.imageBase)
    }

    @Test fun contentThatIsAlreadyCurrentSaysSoOnlyWhenAsked() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.content.fetch = site(content("0", Content.DEFAULT_IMAGE_BASE))
        assertEquals(Outcome.Done, g.content.check(quiet = true))
        assertNull(g.content.status)
        assertEquals(Outcome.Note("Up to date"), g.content.check())
        assertEquals("Up to date", g.content.status)
    }

    @Test fun contentFromANewerSchemaIsLeftToAnAppUpdate() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val manifest = JSONObject().put("version", "3").put("schema", 99).put("file", "x.json").put("sha256", "00")
        g.content.fetch = { manifest.toString().toByteArray() }
        assertEquals(Outcome.Note("Content 3 needs a newer app"), g.content.check())
        assertEquals("0", g.content.content.version)
    }
}
