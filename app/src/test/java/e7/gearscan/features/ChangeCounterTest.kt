package e7.gearscan.features

import e7.gearscan.AppGraph
import e7.gearscan.data.Content
import e7.gearscan.data.Mirror
import e7.gearscan.state.Disk
import e7.gearscan.ui.Outcome
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeCounterTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope) = AppGraph(Disk(createTempDirectory("rev").toFile()), scope)

    private fun site(version: String): (String) -> ByteArray {
        val next = JSONObject().put("version", version).put("contentManifest", Mirror.SITE + "manifest.json")
            .put("images", JSONObject().put("face", "").put("artifact", "").put("base", "https://images.test/"))
            .put("builds", "").put("sets", JSONArray()).put("heroes", JSONArray()).put("artifacts", JSONArray())
        val file = next.toString().toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(java.util.Locale.US, it) }
        val manifest = JSONObject().put("version", version).put("schema", 1).put("file", "content-next.json").put("sha256", sum)
        return { url ->
            when (url) {
                Mirror.SITE + "manifest.json" -> manifest.toString().toByteArray()
                Mirror.SITE + "content-next.json" -> file
                else -> throw IOException("unexpected $url")
            }
        }
    }

    @Test fun touchCountsOneChangeAndSavesTheStateSoon() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        assertFalse(g.disk.exists("state.json"))
        val rev = g.rev.value
        g.rev.bump()
        assertEquals(rev + 1, g.rev.value)
        assertFalse(g.disk.exists("state.json"))
        advanceUntilIdle()
        g.disk.flush()
        assertTrue(g.disk.exists("state.json"))
    }

    @Test fun switchingTheOverGameChoiceCountsOneChange() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val rev = g.rev.value
        g.settings.useOverGameNative(true)
        assertEquals(rev + 1, g.rev.value)
        g.settings.useOverGameNative(false)
        assertEquals(rev + 2, g.rev.value)
    }

    @Test fun anUpdatedContentFileCountsOneChange() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.content.fetch = site("2")
        val rev = g.rev.value
        assertEquals(Outcome.Note("Updated to 2"), g.content.check())
        assertEquals(rev + 1, g.rev.value)
    }

    @Test fun contentThatIsCurrentOrFailedCountsNoChange() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val rev = g.rev.value
        g.content.fetch = { throw IOException("offline") }
        assertTrue(g.content.check() is Outcome.Failed)
        assertEquals(rev, g.rev.value)
        g.content.fetch = site(g.content.content.version)
        g.content.check()
        assertEquals(rev, g.rev.value)
    }
}
