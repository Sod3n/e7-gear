package e7.gearscan.features.findgear

import e7.gearscan.AppGraph
import e7.gearscan.data.PotentialQuery
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature

class FindGearFeature(g: AppGraph) : Feature(g, "findGear") {
    var query by saved("query", PotentialQuery(), Codecs.obj({ it.toJson() }, { PotentialQuery.of(it) }))
}
