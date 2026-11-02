package e7.gearscan

import e7.gearscan.data.Mirror
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/** Which host a content request goes to when the site is blocked, back, or answers with an error. */
class MirrorTest {
    private val img = Mirror.SITE + "img/face/c1001_s.png"
    private val copy = Mirror.COPY + "img/face/c1001_s.png"
    private val asked = mutableListOf<String>()

    private fun host(blocked: Boolean): (String) -> String = { u ->
        asked += u
        if (blocked && u.startsWith(Mirror.SITE)) throw SocketException("Connection reset")
        u
    }

    @After fun reset() { Mirror.active = false }

    @Test
    fun blockedSiteFallsBackAndRemembers() {
        assertEquals(copy, Mirror.fetch(img, get = host(blocked = true)))
        assertTrue(Mirror.active)
        asked.clear()
        assertEquals(copy, Mirror.fetch(img, get = host(blocked = true)))
        assertEquals("the copy goes first once it is the one that answers", listOf(copy), asked)
        assertEquals(copy, Mirror.url(img))
    }

    @Test
    fun contentCheckNoticesTheSiteIsBack() {
        Mirror.active = true
        assertEquals(img, Mirror.fetch(img, siteFirst = true, get = host(blocked = false)))
        assertFalse(Mirror.active)
    }

    @Test
    fun httpErrorsDontSwitchHosts() {
        var thrown = false
        try {
            Mirror.fetch(img) { u -> asked += u; throw IOException("HTTP 404 for $u") }
        } catch (e: IOException) { thrown = true }
        assertTrue(thrown)
        assertEquals(listOf(img), asked)
        assertFalse(Mirror.active)
    }

    @Test
    fun otherHostsAreLeftAlone() {
        val other = "https://example.com/x.png"
        assertEquals(other, Mirror.fetch(other, get = host(blocked = true)))
        assertEquals(other, Mirror.url(other))
    }
}
