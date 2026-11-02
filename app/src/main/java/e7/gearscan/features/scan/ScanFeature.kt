package e7.gearscan.features.scan

import e7.gearscan.AppGraph
import e7.gearscan.data.ScanDiff
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import org.json.JSONObject

class ScanFeature(g: AppGraph) : Feature(g, "scan") {
    var lastScan by saved("lastScan", 0L, Codecs.long)
    var lastDiff by saved("lastDiff", null, DIFF)
    var decodeSec by saved("decodeSec", 0, Codecs.int)
    val boxFile = savedFile("box.json", backup = true, path = "box.json")
    val logFile = savedFile("scan.log", backup = false, path = "scan.log")

    private companion object {
        val DIFF: Codec<ScanDiff?> = Codecs.nullable(Codecs.obj<ScanDiff>({ it.toJson() }, { ScanDiff.fromJson(it)!! }))
    }
}
