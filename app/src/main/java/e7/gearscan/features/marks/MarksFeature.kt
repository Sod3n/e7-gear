package e7.gearscan.features.marks

import e7.gearscan.AppGraph
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import org.json.JSONArray
import org.json.JSONObject

class MarksFeature(g: AppGraph) : Feature(g, "marks") {
    data class Sale(val id: Long, val scan: Long, val kept: Boolean = false)

    var cleared by saved("cleared", emptySet(), Codecs.longSet)
    var kept by saved("kept", emptySet(), Codecs.longSet)
    var sold by saved("sold", emptyList(), SALES)
    var cleanup by saved("cleanup", null, Codecs.nullable(RAW))
    val cleanupProgress = savedFile("cleanup_progress.json", backup = false, path = "cleanup_progress.json")

    private companion object {
        val RAW: Codec<JSONObject> = Codecs.obj({ it }, { it })
        val SALES: Codec<List<Sale>> = object : Codec<List<Sale>> {
            override fun write(value: List<Sale>) = JSONArray(value.map { JSONObject().put("id", it.id).put("scan", it.scan).put("kept", it.kept) })
            override fun read(json: Any?): List<Sale>? = (json as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching { a.getJSONObject(i).let { Sale(it.getLong("id"), it.getLong("scan"), it.optBoolean("kept")) } }.getOrNull()
                }
            }
        }
    }
}
