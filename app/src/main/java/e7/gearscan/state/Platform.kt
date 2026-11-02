package e7.gearscan.state

import android.net.Uri

interface Platform {
    fun findGame(): String?

    fun otherVpnActive(): Boolean

    val pillAllowed: Boolean

    fun allowPill()

    fun startCapture(
        game: String,
        upstream: String,
    )

    fun stopCapture()

    fun resetCaptureClock()

    fun openGame(game: String)

    fun openGameSettings(game: String)

    fun stopOverlay(onlyWhenTracking: Boolean = false)

    fun export(
        name: String,
        text: String,
    ): Pair<Uri, String>

    fun read(uri: Uri): String?
}

object NoPlatform : Platform {
    override fun findGame(): String? = null

    override fun otherVpnActive() = false

    override val pillAllowed = false

    override fun allowPill() {}

    override fun startCapture(
        game: String,
        upstream: String,
    ) {}

    override fun stopCapture() {}

    override fun resetCaptureClock() {}

    override fun openGame(game: String) {}

    override fun openGameSettings(game: String) {}

    override fun stopOverlay(onlyWhenTracking: Boolean) {}

    override fun export(
        name: String,
        text: String,
    ): Pair<Uri, String> = throw UnsupportedOperationException("no device")

    override fun read(uri: Uri): String? = null
}
