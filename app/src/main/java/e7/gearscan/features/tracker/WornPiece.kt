package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.Box
import e7.gearscan.data.Item
import org.json.JSONObject

class WornPiece(private val t: TrackerFeature, private val g: AppGraph) {
    private class EquipUndo(val itemId: Long, val before: JSONObject, val owner: Long?, val displaced: Item?, val heroId: Long)

    private var equipUndo: EquipUndo? by mutableStateOf(null)
    var equippedOn: String? by mutableStateOf(null)
        private set
    val canUndoEquip get() = equipUndo != null

    fun clear() {
        equipUndo = null
        equippedOn = null
    }

    // the piece as recorded, now worn by the hero: its stats and owner go into the g.scan.box (a new g.scan.lastScan replaces them)
    fun equip(heroId: Long) {
        val b = g.scan.box ?: return
        val r = t.record ?: return
        if (r.scan != g.scan.lastScan || b.itemsById[r.item.id] == null) {
            t.error = "Your g.scan.lastScan changed. Choose the piece again."
            return
        }
        val owner = b.itemsById.getValue(r.item.id).equippedOn
        val displaced = b.gearOf(heroId)[r.item.slot]?.takeIf { it.id != r.item.id }
        val before = b.writeStats(r.item) ?: return
        val next = Box(b.json)
        next.itemsById[r.item.id]?.let { next.setEquipped(it, heroId) }
        displaced?.let { d -> next.itemsById[d.id]?.let { next.setEquipped(it, null) } }
        persistBox(next)
        equippedOn = g.heroes.heroName(heroId) ?: "the hero"
        equipUndo = EquipUndo(r.item.id, before, owner, displaced, heroId)
    }

    fun undoEquip() {
        val u = equipUndo ?: return
        g.scan.box?.let { cur ->
            cur.restore(u.itemId, u.before)
            val back = Box(cur.json)
            back.itemsById[u.itemId]?.let { back.setEquipped(it, u.owner) }
            u.displaced?.let { d -> back.itemsById[d.id]?.let { back.setEquipped(it, u.heroId) } }
            persistBox(back)
        }
        equipUndo = null
        equippedOn = null
    }

    private fun persistBox(b: Box) {
        g.scan.replaceBox(b)
        g.scan.touch()
        g.scan.saveBox()
        g.store.saveNow()
    }
}
