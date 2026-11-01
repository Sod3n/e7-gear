package e7.gearscan.data

import java.net.URL
import java.net.URLConnection

/** Every request names the app and its version, so the servers it talks to (Fribbels' above all) can tell its traffic apart. */
object Http {
    @Volatile
    var agent = "E7Gear"

    // Fribbels' free service (builds, scan import) answered 403 after test bursts; instrumented tests never reach it
    @Volatile
    var blockThirdParty = runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess

    fun thirdParty(url: String) = URL(url).host.endsWith(".amazonaws.com")

    fun open(url: String): URLConnection {
        if (blockThirdParty && thirdParty(url)) throw java.io.IOException("third-party request blocked in tests: ${URL(url).host}")
        return URL(url).openConnection().apply { setRequestProperty("User-Agent", agent) }
    }
}
