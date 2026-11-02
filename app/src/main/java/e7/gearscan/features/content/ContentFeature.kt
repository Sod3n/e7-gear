package e7.gearscan.features.content

import e7.gearscan.AppGraph
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature

class ContentFeature(g: AppGraph) : Feature(g, "content") {
    var checkedAt by saved("checkedAt", 0L, Codecs.long, keepOnErase = true)
    var mirror by saved("mirror", false, Codecs.bool, keepOnErase = true)
    val file = savedFile("content.json", backup = false, keepOnErase = true, path = "content.json")
}
