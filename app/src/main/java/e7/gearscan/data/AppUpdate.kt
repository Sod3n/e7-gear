package e7.gearscan.data

import org.json.JSONObject

/** New app versions: sideloaded installs learn about them from the repo's latest GitHub release. */
object AppUpdate {
    const val LATEST = "https://api.github.com/repos/Sod3n/e7-gear/releases/latest"

    class Release(val version: String, val page: String)

    /** The latest release when it's newer than [installed] ("1.0.0"); tags are "v1.0.1". */
    fun newer(installed: String, latestJson: String): Release? {
        val j = JSONObject(latestJson)
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        val version = j.getString("tag_name").removePrefix("v")
        return if (compare(version, installed) > 0) Release(version, j.getString("html_url")) else null
    }

    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d }
        return 0
    }
}
