package e7.gearscan

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import e7.gearscan.overlay.OverlayService
import e7.gearscan.overlay.OverlayState
import e7.gearscan.state.Platform

class AndroidPlatform(private val app: Application) : Platform {
    override fun findGame(): String? =
        app.packageManager.getInstalledApplications(0).map { it.packageName }
            .firstOrNull { it.lowercase().let { p -> "epic7" in p || "epicseven" in p } }

    override fun otherVpnActive(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    override val pillAllowed get() = android.provider.Settings.canDrawOverlays(app)

    override fun allowPill() {
        app.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun startCapture(
        game: String,
        upstream: String,
    ) {
        app.startForegroundService(
            Intent(app, TProxyService::class.java)
                .setAction(TProxyService.ACTION_CONNECT)
                .putExtra(TProxyService.EXTRA_GAME, game)
                .putExtra(TProxyService.EXTRA_UPSTREAM, upstream),
        )
    }

    override fun stopCapture() {
        app.startService(Intent(app, TProxyService::class.java).setAction(TProxyService.ACTION_DISCONNECT))
    }

    override fun resetCaptureClock() {
        app.startService(Intent(app, TProxyService::class.java).setAction(TProxyService.ACTION_RESET_CLOCK))
    }

    override fun openGame(game: String) {
        app.packageManager.getLaunchIntentForPackage(game)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun openGameSettings(game: String) {
        app.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", game, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun stopOverlay(onlyWhenTracking: Boolean) {
        if (!onlyWhenTracking || OverlayState.upgrade != null) OverlayService.stop(app)
    }

    override fun export(
        name: String,
        text: String,
    ): Pair<Uri, String> {
        val dir = Environment.DIRECTORY_DOWNLOADS + "/E7GearScan"
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        app.contentResolver.delete(
            coll,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$dir/", name),
        )

        fun insert(n: String) =
            app.contentResolver.insert(
                coll,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, n)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                },
            )!!
        // copies left by an earlier install aren't ours to replace, and Android numbers a name only up to 31 times
        var saved = name
        val uri =
            runCatching { insert(name) }.getOrElse {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                saved = name.substringBeforeLast('.') + "-$stamp." + name.substringAfterLast('.')
                insert(saved)
            }
        app.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return uri to saved
    }

    override fun read(uri: Uri): String? =
        runCatching {
            app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.getOrNull()
}
