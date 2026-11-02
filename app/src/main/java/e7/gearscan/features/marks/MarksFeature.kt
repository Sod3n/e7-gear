package e7.gearscan.features.marks

import e7.gearscan.state.FeatureContext
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.data.Box
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.ui.Outcome
import org.json.JSONArray
import org.json.JSONObject

class MarksFeature(ctx: FeatureContext, private val scanFeature: ScanFeature) : Feature(ctx, "marks") {
    data class Sale(val id: Long, val scan: Long, val kept: Boolean = false)

    var cleared by saved("cleared", emptySet(), Codecs.longSet)
        private set
    var kept by saved("kept", emptySet(), Codecs.longSet)
        private set
    var sold by saved("sold", emptyList(), SALES)
        private set

    val reviewed: Set<Long> get() = cleared + kept

    var onChange: () -> Unit = {}

    fun markReviewed(id: Long, keep: Boolean): Outcome {
        if (id in reviewed) return Outcome.Done
        if (keep) kept = kept + id else cleared = cleared + id
        changed()
        return Outcome.Note(if (keep) "Kept · won't come back until you unlock it" else "Marked sold")
    }

    fun unmark(id: Long) {
        kept = kept - id
        cleared = cleared - id
        sold = sold.filterNot { it.id == id }
        changed()
    }

    fun recordSold(id: Long, scan: Long, keep: Boolean) {
        sold = sold.filterNot { it.id == id && it.scan == scan } + Sale(id, scan, keep)
        if (keep) kept = kept + id else cleared = cleared + id
        changed()
    }

    fun lastSale(scan: Long, queued: Set<Long>): Sale? = sold.lastOrNull { it.scan == scan && it.id in reviewed && it.id in queued }

    fun undoSale(sale: Sale) {
        sold = sold - sale
        if (sale.kept) kept = kept - sale.id else cleared = cleared - sale.id
        changed()
    }

    override fun onBoxChanged(old: Box?, new: Box?) {
        val present = { id: Long -> new?.itemsById?.containsKey(id) == true }
        val keptNow = kept.filter(present).toSet()
        val clearedNow = cleared.filter(present).toSet()
        val soldNow = sold.filter { it.id in clearedNow + keptNow && it.scan == scanFeature.lastScan }.distinctBy { it.id }
        if (keptNow == kept && clearedNow == cleared && soldNow == sold) return
        kept = keptNow
        cleared = clearedNow
        sold = soldNow
        changed()
    }

    private fun changed() {
        ctx.store.saveNow()
        onChange()
    }

    private companion object {
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
