package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.data.Mod
import e7.gearscan.overlay.OverlayBuild
import e7.gearscan.state.Feature
import org.json.JSONArray
import org.json.JSONObject

class ChecklistFeature(g: AppGraph) : Feature(g, "checklist") {
    val progress = savedFile("checklist.json", backup = false, path = "checklist.json")

    var build: OverlayBuild? by mutableStateOf(null)
        private set
    val done = mutableStateListOf<Boolean>()
    var focus by mutableStateOf(-1)
    var recorded by mutableStateOf(false)
        internal set

    private val rolled = mutableStateMapOf<Int, Double>()
    private val doneOrder = mutableStateListOf<Int>()
    private var onFinish: (() -> Unit)? = null

    val current: Int get() = focus.takeIf { it >= 0 && it < done.size && !done[it] } ?: done.indexOfFirst { !it }
    val lastDone: Int? get() = doneOrder.lastOrNull()

    fun load(
        build: OverlayBuild,
        onFinish: () -> Unit,
    ) {
        g.tracker.hide()
        this.build = build
        done.clear()
        done.addAll(build.pieces.map { it.onHero && !it.otherMenu })
        doneOrder.clear()
        rolled.clear()
        restoreProgress(build)
        g.overlay.expanded = false
        g.overlay.tab = 0
        focus = -1
        this.onFinish = onFinish
        recorded = false
    }

    fun unload() {
        build = null
    }

    fun finish() {
        onFinish?.invoke()
        recorded = true
        build?.let { b -> editProgress { it.remove(b.key) } }
    }

    fun markCurrentDone() {
        val i = current.takeIf { it >= 0 } ?: return
        done[i] = true
        doneOrder.add(i)
        focus = -1
        saveProgress()
    }

    fun undo() {
        val i = doneOrder.removeLastOrNull() ?: return
        done[i] = false
        focus = i
        saveProgress()
    }

    fun rolledValue(i: Int): Double? = rolled[i] ?: build?.pieces?.getOrNull(i)?.item?.mod?.toValue

    fun setRolled(
        i: Int,
        v: Double,
    ) {
        rolled[i] = v
        saveProgress()
    }

    fun rolledMods(): List<Pair<Long, Mod>> =
        build?.pieces.orEmpty().mapIndexedNotNull { i, p ->
            val m = p.item.mod?.takeIf { p.modStep } ?: return@mapIndexedNotNull null
            p.item.id to Mod(m.index, m.from, m.fromValue, m.to, rolledValue(i) ?: m.toValue)
        }

    private fun readProgress() = runCatching { JSONObject(progress.read()!!) }.getOrElse { JSONObject() }

    private fun editProgress(edit: (JSONObject) -> Unit) {
        val all = readProgress().also(edit)
        val keep = all.keys().asSequence().sortedByDescending { all.getJSONObject(it).optLong("at") }.take(KEPT).toSet()
        all.keys().asSequence().toList().filter { it !in keep }.forEach { all.remove(it) }
        progress.write(all.toString())
    }

    private fun restoreProgress(b: OverlayBuild) {
        val o = readProgress().optJSONObject(b.key) ?: return
        val d = o.getJSONArray("done")
        if (d.length() != done.size) return
        for (i in done.indices) done[i] = d.getBoolean(i)
        val order = o.getJSONArray("order")
        for (i in 0 until order.length()) doneOrder.add(order.getInt(i))
        o.optJSONObject("rolled")?.let { r -> r.keys().forEach { k -> rolled[k.toInt()] = r.getDouble(k) } }
    }

    private fun saveProgress() {
        val b = build ?: return
        editProgress {
            it.put(
                b.key,
                JSONObject().put("at", System.currentTimeMillis())
                    .put("done", JSONArray(done.toList())).put("order", JSONArray(doneOrder.toList()))
                    .put("rolled", JSONObject().apply { rolled.forEach { (i, v) -> put(i.toString(), v) } }),
            )
        }
    }

    private companion object {
        const val KEPT = 5
    }
}
