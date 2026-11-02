package e7.gearscan.data

import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The content site has a copy on Google Cloud Storage (tools/mirror_content.sh): some countries block *.pages.dev
 * by name, which resets the connection. A site request that can't connect is retried on the copy, and requests go
 * to the copy while it is the one that answers.
 */
object Mirror {
    const val SITE = "https://e7-gear-content.pages.dev/"
    const val COPY = "https://storage.googleapis.com/e7-gear-content/"

    /** The copy answered where the site couldn't; saved, so the next start doesn't wait on the site first. */
    @Volatile var active = false

    fun url(u: String) = if (active && u.startsWith(SITE)) COPY + u.removePrefix(SITE) else u

    /**
     * [get]s [u] from whichever host answers, trying the one that worked last time first. [siteFirst] asks the site
     * before the copy, so a content check notices when the site is reachable again.
     */
    fun <T> fetch(u: String, siteFirst: Boolean = false, get: (String) -> T): T {
        if (!u.startsWith(SITE)) return get(u)
        val copy = COPY + u.removePrefix(SITE)
        val first = if (active && !siteFirst) copy else u
        val second = if (first == u) copy else u
        return try {
            get(first).also { active = first == copy }
        } catch (e: IOException) {
            if (!unreachable(e)) throw e
            get(second).also { active = second == copy }
        }
    }

    // an HTTP error means the host answered; only a connection that never got through is worth the other host
    private fun unreachable(e: IOException) = e is SocketException || e is SSLException || e is UnknownHostException ||
        e is java.io.InterruptedIOException
}
