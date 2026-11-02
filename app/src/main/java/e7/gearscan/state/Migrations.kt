package e7.gearscan.state

import org.json.JSONArray
import org.json.JSONObject

object Migrations {
    val all = listOf(Migration(1, ::v1ToV2))

    private val copied = listOf(
        "lastScan" to "scan.lastScan", "lastDiff" to "scan.lastDiff", "decodeSec" to "scan.decodeSec",
        "configs" to "heroes.configs",
        "consent" to "settings.consent", "proxyOn" to "settings.proxyOn", "proxy" to "settings.proxy",
        "showRating" to "settings.showRating", "overlaySeen" to "settings.overlaySeen",
        "overGameNative" to "settings.overGameNative", "nativeSeen" to "settings.nativeSeen",
        "poolTiers" to "pool.tiers", "poolExtra" to "pool.extra", "poolSkip" to "pool.skip",
        "poolLosses" to "pool.losses", "poolEffort" to "pool.effort", "poolSecondsPerHero" to "pool.secondsPerHero",
        "pendingPool" to "pool.pending",
        "contentCheckedAt" to "content.checkedAt", "contentMirror" to "content.mirror",
        "cleanup" to "marks.cleanup", "cleanupDone" to "marks.cleared", "sellKept" to "marks.kept", "overlaySales" to "marks.sold",
        "sellQuery" to "sellReview.query", "potentialQuery" to "findGear.query",
        "heroUpgradeMinGain" to "improveHeroes.minGain", "heroUpgradeRebuilds" to "improveHeroes.rebuilds",
    )

    private fun v1ToV2(json: JSONObject): JSONObject {
        if (json.optString("app") != "e7gear") return JSONObject().put("fields", fields(json))
        val out = JSONObject().put("app", "e7gear").put("fields", fields(json.optJSONObject("state") ?: JSONObject()))
        val files = JSONObject()
        val untouched = JSONArray()
        json.optJSONObject("box")?.let { files.put("box.json", it.toString()) } ?: untouched.put("box.json")
        json.optJSONArray("history")?.let { files.put("history.json", it.toString()) } ?: untouched.put("history.json")
        json.optJSONObject("upgrades")?.let { files.put("upgrades.json", it.toString()) }
        return out.put("files", files).put("untouched", untouched)
    }

    private fun fields(v1: JSONObject): JSONObject {
        val out = JSONObject()
        copied.forEach { (old, new) -> if (v1.has(old) && !v1.isNull(old)) out.put(new, v1.get(old)) }
        out.put("heroes.tiers", v1.optJSONObject("tiers") ?: JSONObject().also { o ->
            v1.optJSONArray("priority")?.let { a -> for (i in 0 until a.length()) o.put(a.optLong(i).toString(), 3) }
        })
        v1.optJSONArray("team")?.takeIf { it.length() > 0 }?.let { out.put("heroes.team", JSONArray().put(it.get(0))) }
        if (v1.has("lastPoolAt") && v1.has("lastPoolScore")) {
            out.put("pool.lastPool", JSONObject().put("at", v1.get("lastPoolAt")).put("score", v1.get("lastPoolScore")))
        }
        return out
    }
}
