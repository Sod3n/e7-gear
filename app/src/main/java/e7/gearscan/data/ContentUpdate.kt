package e7.gearscan.data

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Game content from the content site, so new heroes, artifacts and numbers reach players without an app update.
 * The site's manifest names the newest content file with its checksum and schema; content this app can't read
 * (a newer schema) is left for an app update, and content that lost heroes, sets or artifacts is refused.
 */
object ContentUpdate {
    /** Newest content layout this app understands; bump with any change an older app would misread. */
    const val SCHEMA = 1

    enum class Result { CURRENT, UPDATED, NEEDS_APP }

    class Outcome(val result: Result, val content: Content? = null, val bytes: ByteArray? = null, val version: String? = null)

    /** [fetch] returns a URL's body or throws; nothing is saved here, the caller keeps [Outcome.bytes] when UPDATED. */
    fun check(current: Content, fetch: (String) -> ByteArray): Outcome {
        val manifestUrl = current.manifestUrl ?: error("no content site")
        val m = JSONObject(String(fetch(manifestUrl)))
        val version = m.getString("version")
        if (version <= current.version) return Outcome(Result.CURRENT, version = version)
        if (m.getInt("schema") > SCHEMA) return Outcome(Result.NEEDS_APP, version = version)
        val bytes = fetch(URI(manifestUrl).resolve(m.getString("file")).toString())
        val sum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.US, it) }
        check(sum == m.getString("sha256")) { "content checksum doesn't match" }
        val next = Content.parse(String(bytes))
        check(next.version == version) { "content version ${next.version} isn't the manifest's $version" }
        problem(current, next)?.let { error(it) }
        return Outcome(Result.UPDATED, next, bytes, version)
    }

    /** Why [next] must not replace [old]: a broken export loses heroes, sets or artifacts it had. */
    fun problem(old: Content, next: Content): String? = when {
        next.schema > SCHEMA -> "content needs a newer app"
        next.heroes.size < old.heroes.size -> "content has fewer heroes (${next.heroes.size} < ${old.heroes.size})"
        next.sets.size < old.sets.size -> "content has fewer sets"
        next.artifacts.size < old.artifacts.size -> "content has fewer artifacts"
        else -> null
    }
}
