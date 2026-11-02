package e7.gearscan.features.heroes

import e7.gearscan.AppGraph
import e7.gearscan.data.HeroConfig
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import org.json.JSONObject

class HeroesFeature(g: AppGraph) : Feature(g, "heroes") {
    var tiers by saved("tiers", emptyMap(), TIERS)
    var team by saved("team", emptyList(), Codecs.longList)
    private var storedConfigs by saved("configs", emptyMap(), CONFIGS)
    val builds = savedDir("builds")

    val configs: MutableMap<Long, HeroConfig>
        get() {
            val s = storedConfigs
            return if (s is HashMap) s else HashMap(s).also { storedConfigs = it }
        }

    fun adoptConfigs() { configs }

    private companion object {
        val TIERS: Codec<Map<Long, Int>> = Codecs.obj(
            { m -> JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v) } } },
            { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } },
        )
        val CONFIGS: Codec<Map<Long, HeroConfig>> = Codecs.obj(
            { m -> JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v.toJson()) } } },
            { o ->
                val out = HashMap<Long, HeroConfig>()
                o.keys().forEach { k -> runCatching { out[k.toLong()] = HeroConfig.fromJson(o.getJSONObject(k)) } }
                out
            },
        )
    }
}
