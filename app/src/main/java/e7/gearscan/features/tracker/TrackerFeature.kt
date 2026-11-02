package e7.gearscan.features.tracker

import e7.gearscan.AppGraph
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight

class TrackerFeature(g: AppGraph) : Feature(g, "tracker") {
    val upgrades = savedFile("upgrades.json", backup = true, path = "upgrades.json")
    val checklist = savedFile("checklist.json", backup = false, path = "checklist.json")
    val check = work<Unit>("Upgrade check", Weight.Yielding, foreground = true)
}
