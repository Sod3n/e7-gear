package e7.gearscan.state

import android.net.Uri
import java.io.File

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

    val appVersion: String

    fun openPage(url: String)

    fun updateFile(version: String): File

    fun download(
        url: String,
        to: File,
        size: Long,
        progress: (Float) -> Unit,
    )

    fun refusal(apk: File): String?

    val canInstall: Boolean

    fun install(apk: File)

    fun allowInstalls()

    fun cleanUpdates(installed: String)

    fun notifyPoolDone(heroes: Int)
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

    override val appVersion = "0"

    override fun openPage(url: String) {}

    override fun updateFile(version: String) = File("E7-Gear-$version.apk")

    override fun download(
        url: String,
        to: File,
        size: Long,
        progress: (Float) -> Unit,
    ) {}

    override fun refusal(apk: File): String? = null

    override val canInstall = false

    override fun install(apk: File) {}

    override fun allowInstalls() {}

    override fun cleanUpdates(installed: String) {}

    override fun notifyPoolDone(heroes: Int) {}
}
