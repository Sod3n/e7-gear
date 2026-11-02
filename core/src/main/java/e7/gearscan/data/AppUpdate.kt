package e7.gearscan.data

import org.json.JSONObject

/** New app versions: sideloaded installs learn about them from the repo's latest GitHub release. */
object AppUpdate {
    const val LATEST = "https://api.github.com/repos/Sod3n/e7-gear/releases/latest"

    /** [apk]: the release's APK download, null when the release has none (then only the page helps). */
    class Release(val version: String, val page: String, val apk: String? = null, val size: Long = 0)

    /** The latest release when it's newer than [installed] ("1.0.0"); tags are "v1.0.1". */
    fun newer(installed: String, latestJson: String): Release? {
        val j = JSONObject(latestJson)
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        val version = j.getString("tag_name").removePrefix("v")
        if (compare(version, installed) <= 0) return null
        val assets = j.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
        return Release(version, j.getString("html_url"), apk?.getString("browser_download_url"), apk?.optLong("size") ?: 0)
    }

    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d }
        return 0
    }
}
