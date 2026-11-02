package e7.gearscan.features.pool

import e7.gearscan.AppGraph
import e7.gearscan.opt.PoolEffort
import e7.gearscan.opt.PoolLosses
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import org.json.JSONObject

class PoolFeature(g: AppGraph) : Feature(g, "pool") {
    var tiers by saved("tiers", setOf(5, 4, 3), Codecs.intSet)
    var extra by saved("extra", emptySet(), Codecs.longSet)
    var skip by saved("skip", emptySet(), Codecs.longSet)
    var losses by saved("losses", PoolLosses.SAFE, Codecs.enum(PoolLosses.entries.toTypedArray()))
    var effort by saved("effort", PoolEffort.BALANCED, Codecs.enum(PoolEffort.entries.toTypedArray()))
    var secondsPerHero by saved("secondsPerHero", 3.0, Codecs.double)
    var pending by saved("pending", null, Codecs.nullable(Codecs.long))
    var lastPool by saved("lastPool", null, LAST)
    val run = work<Unit>("Pool run", Weight.Heavy)

    private companion object {
        val LAST: Codec<Pair<Long, Int>?> = Codecs.nullable(
            Codecs.obj<Pair<Long, Int>>({ JSONObject().put("at", it.first).put("score", it.second) }, { it.getLong("at") to it.getInt("score") }),
        )
    }
}
