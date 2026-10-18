package e7.gearscan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import e7.gearscan.data.AppUpdate
import e7.gearscan.data.Http
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

/** Self-update: fetches a release's APK and hands it to Android's installer once it's checked to be this app, newer. */
object AppInstall {
    private const val AUTHORITY = "e7.gearscan.updates"

    fun file(ctx: Context, version: String) = File(File(ctx.cacheDir, "updates"), "E7-Gear-$version.apk")

    /** Why [apk] mustn't go over this app, or null when it may: same package, same signing key, newer version. */
    fun refusal(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val new = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "the download isn't an app"
        val own = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(p: PackageInfo) = p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        return when {
            new.packageName != ctx.packageName -> "it's a different app (${new.packageName})"
            signers(new).isEmpty() || signers(new) != signers(own) -> "it isn't signed with this app's key"
            new.longVersionCode <= own.longVersionCode -> "it isn't newer than this version"
            else -> null
        }
    }

    /** Downloads [url] to [to], continuing a part left by an earlier try; [size] (when known) must match. */
    fun download(url: String, to: File, size: Long, progress: (Float) -> Unit) {
        to.parentFile!!.mkdirs()
        val part = File(to.path + ".part")
        val c = Http.open(url)
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
        val append = c is HttpURLConnection && c.responseCode == 206
        if (c is HttpURLConnection && c.responseCode !in listOf(200, 206)) error("HTTP ${c.responseCode}")
        val total = if (size > 0) size else (if (append) part.length() else 0) + c.contentLengthLong
        var done = if (append) part.length() else 0L
        c.getInputStream().use { input -> FileOutputStream(part, append).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n); done += n
                if (total > 0) progress(done.toFloat() / total)
            }
        } }
        if (size > 0 && part.length() != size) { part.delete(); error("got ${part.length()} of $size bytes") }
        if (!part.renameTo(to)) error("couldn't save it")
    }

    /** Drops downloads of versions this app already is (or is past). */
    fun clean(ctx: Context, installed: String) {
        File(ctx.cacheDir, "updates").listFiles()?.forEach {
            if (AppUpdate.compare(it.name.removePrefix("E7-Gear-").substringBefore(".apk"), installed) <= 0) it.delete()
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
