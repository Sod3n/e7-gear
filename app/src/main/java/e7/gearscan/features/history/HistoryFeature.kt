package e7.gearscan.features.history

import e7.gearscan.AppGraph
import e7.gearscan.state.Feature

class HistoryFeature(g: AppGraph) : Feature(g, "history") {
    val file = savedFile("history.json", backup = true, path = "history.json")
}
