package e7.gearscan.data

import org.json.JSONObject

/** Game content (heroes, artifacts, sets); shipped in assets and refreshed from the server. */
class Content(val version: String, json: JSONObject) {
    val faceUrl: String = json.getJSONObject("images").getString("face")
    val artifactUrl: String = json.getJSONObject("images").getString("artifact")
    /** Where the game-derived images (icons, gear glyphs, artifact icons) are served from. */
    val imageBase: String = json.getJSONObject("images").optString("base").ifEmpty { DEFAULT_IMAGE_BASE }
    val buildsUrl: String = json.getString("builds")
    val feedbackUrl: String? = json.optString("feedback").takeIf { it.isNotEmpty() }
    val rtaBuildsUrl: String? = json.optString("rtaBuilds").takeIf { it.isNotEmpty() }
    /** Layout version of this file; see [ContentUpdate.SCHEMA]. */
    val schema: Int = json.optInt("schema", 1)
    /** Where newer content is announced; none in content made before remote updates. */
    val manifestUrl: String? = json.optString("contentManifest").takeIf { it.isNotEmpty() }
    val sets: List<SetDef>
    val heroes: Map<String, GameHero>
    val artifacts: List<Artifact>
    private val setIndex: Map<String, Int>
    private val setByGame: Map<String, String>

    init {
        val sa = json.getJSONArray("sets")
        sets = (0 until sa.length()).map { i ->
            val o = sa.getJSONObject(i)
            SetDef(o.getString("key"), o.optString("game"), o.getString("name"), o.getInt("pieces"), o.getString("icon"),
                o.optDouble("atkPct", 0.0), o.optDouble("hpPct", 0.0), o.optDouble("defPct", 0.0),
                o.optDouble("spdPct", 0.0), o.optDouble("cr", 0.0), o.optDouble("cd", 0.0),
                o.optDouble("eff", 0.0), o.optDouble("res", 0.0), o.optDouble("dmg", 0.0), o.optInt("pen", 0) == 1)
        }
        setIndex = sets.withIndex().associate { it.value.key to it.index }
        setByGame = sets.associate { it.game to it.key }
        val ha = json.getJSONArray("heroes")
        heroes = (0 until ha.length()).associate { i ->
            val o = ha.getJSONObject(i)
            val imp = o.optJSONObject("imprint")
            val ee = o.optJSONArray("ee")
            o.getString("code") to GameHero(
                o.getString("code"), o.optStringOrNull("name"), o.optStringOrNull("element"), o.optStringOrNull("role"),
                stats(o.getJSONObject("base6")), o.optJSONObject("base5")?.let(::stats),
                imp?.optStringOrNull("type"), imp?.optDouble("value", 0.0) ?: 0.0,
                (0 until (ee?.length() ?: 0)).map { ee!!.getJSONObject(it).let { e -> e.getString("type") to e.getDouble("value") } },
                o.optJSONArray("skills")?.let { a -> (0 until a.length()).map { k -> a.getJSONObject(k).let { s ->
                    SkillDmg(s.getInt("n"), s.optDouble("rate", 0.0), s.optDouble("pow", 1.0), s.optDouble("hp", 0.0), s.optDouble("def", 0.0),
                        s.optDouble("spd", 0.0), s.optDouble("hpMul", 0.0), s.optBoolean("noCrit", false))
                } } }.orEmpty())
        }
        val aa = json.getJSONArray("artifacts")
        artifacts = (0 until aa.length()).map { i ->
            val o = aa.getJSONObject(i)
            // "" means every class can wear it
            Artifact(o.getString("code"), o.getString("name"), o.optStringOrNull("role")?.takeIf { it.isNotEmpty() },
                o.optDouble("atk", 0.0), o.optDouble("hp", 0.0), o.optDouble("def", 0.0), o.optInt("rarity", 5))
        }
    }

    fun setIndex(key: String): Int = setIndex[key] ?: -1
    fun setByName(name: String): SetDef? = sets.firstOrNull { it.name.equals(name, true) }
    fun set(key: String): SetDef? = setIndex[key]?.let { sets[it] } ?: null.also { Problems.report("unknown set '$key'") }
    fun setFromGame(game: String): String? = setByGame[game] ?: null.also { Problems.report("unknown game set '$game'") }
    fun artifact(code: String?): Artifact? = artifacts.firstOrNull { it.code == code }
    fun face(code: String) = faceUrl.replace("{code}", code)
    fun artifactIcon(code: String) = artifactUrl.replace("{code}", code)

    private fun stats(o: JSONObject) = Stats8(o.optDouble("atk", 0.0), o.optDouble("hp", 0.0), o.optDouble("def", 0.0),
        o.optDouble("spd", 0.0), o.optDouble("cr", 0.0), o.optDouble("cd", 0.0), o.optDouble("eff", 0.0),
        o.optDouble("res", 0.0))

    companion object {
        const val DEFAULT_IMAGE_BASE = Mirror.SITE + "img/"

        /** Before the first download: no game data at all, only where to get it (the APK ships none). */
        val EMPTY: Content by lazy {
            parse(JSONObject().put("version", "0").put("contentManifest", Mirror.SITE + "manifest.json")
                .put("images", JSONObject().put("face", "").put("artifact", "")).put("builds", "")
                .put("sets", org.json.JSONArray()).put("heroes", org.json.JSONArray()).put("artifacts", org.json.JSONArray()).toString())
        }

        fun parse(text: String): Content {
            val j = JSONObject(text)
            return Content(j.getString("version"), j)
        }
    }
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
