package e7.gearscan.features.improveheroes

import e7.gearscan.AppGraph
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature

class ImproveHeroesFeature(g: AppGraph) : Feature(g, "improveHeroes") {
    var minGain by saved("minGain", 0.5, Codecs.double)
    var rebuilds by saved("rebuilds", false, Codecs.bool)
    val result = savedFile("hero_upgrades.json", backup = false, path = "hero_upgrades.json")
    val progress = savedFile("hero_upgrades_progress.json", backup = false, path = "hero_upgrades_progress.json")
}
