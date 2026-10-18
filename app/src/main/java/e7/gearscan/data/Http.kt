package e7.gearscan.data

import java.net.URL
import java.net.URLConnection

/** Every request names the app and its version, so the servers it talks to (Fribbels' above all) can tell its traffic apart. */
object Http {
    @Volatile
    var agent = "E7Gear"

    fun open(url: String): URLConnection = URL(url).openConnection().apply { setRequestProperty("User-Agent", agent) }
}
