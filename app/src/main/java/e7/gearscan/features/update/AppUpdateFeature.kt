package e7.gearscan.features.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.data.AppUpdate
import e7.gearscan.state.Feature
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUpdateFeature(ctx: FeatureContext) : Feature(ctx, "appUpdate") {
    /** A newer app version on GitHub Releases, found by the daily check. */
    var release: AppUpdate.Release? by mutableStateOf(null)
        internal set

    /** The self-update's download, 0..1; null when none is running. */
    var progress: Float? by mutableStateOf(null)
        private set

    var problem: String? by mutableStateOf(null)
        private set

    // downloaded and checked, waiting for the player to allow installs from this app
    private var installWhenAllowed: File? = null

    val version: String get() = ctx.platform.appVersion

    /** Sideloaded installs hear about new versions only from here; quiet on any failure. */
    suspend fun check() {
        val installed = version
        release =
            withContext(Dispatchers.IO) {
                runCatching {
                    val c = ctx.http.open(AppUpdate.LATEST) as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 15000
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    if (c.responseCode != 200) null else AppUpdate.newer(installed, c.inputStream.bufferedReader().use { it.readText() })
                }.getOrNull()
            }
    }

    fun checkNow() {
        ctx.scope.launch { check() }
    }

    suspend fun cleanOld() {
        val installed = version
        withContext(Dispatchers.IO) { ctx.platform.cleanUpdates(installed) }
    }

    /** Downloads [release], checks it is this app signed with this key and newer, then opens Android's installer. */
    fun update() {
        val u = release ?: return
        if (u.apk == null) {
            ctx.platform.openPage(u.page)
            return
        }
        if (progress != null) return
        problem = null
        progress = 0f
        ctx.scope.launch {
            val apk = ctx.platform.updateFile(u.version)
            val failed =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (!apk.exists()) {
                            ctx.platform.download(ctx.http, u.apk!!, apk, u.size) { p -> if ((p * 100).toInt() != ((progress ?: 0f) * 100).toInt()) progress = p }
                        }
                        ctx.platform.refusal(apk)
                    }.getOrElse { if (it is IOException) "" else "the download failed (${it.message})" }
                }
            // the problem is set before progress clears, so an idle state never shows without it
            when {
                failed != null -> {
                    apk.delete()
                    problem = if (failed.isEmpty()) "Download stopped. Tap to try again." else "Not installed: $failed."
                    progress = null
                }
                ctx.platform.canInstall -> {
                    progress = null
                    ctx.platform.install(apk)
                }
                else -> {
                    installWhenAllowed = apk
                    problem = "Allow installs from E7 Gear, then come back."
                    progress = null
                    ctx.platform.allowInstalls()
                }
            }
        }
    }

    /** Back from the "Install unknown apps" switch: installs the waiting update once it's allowed. */
    fun resumed() {
        val apk = installWhenAllowed ?: return
        if (!ctx.platform.canInstall) return
        installWhenAllowed = null
        problem = null
        ctx.platform.install(apk)
    }
}
