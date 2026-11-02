package e7.gearscan.features.tracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.calc.Calc
import e7.gearscan.calc.Reforge
import e7.gearscan.data.Item
import e7.gearscan.data.Mod
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.opt.Build
import e7.gearscan.overlay.OverlayBuild
import e7.gearscan.overlay.OverlayPiece
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

    private val content get() = g.content.content
    private val box get() = g.scan.box

    /** Loads one hero's build as the in-game checklist; finishing it records the build. */
    fun loadBuild(heroId: Long, b: Build, title: String, flag: String? = null) {
        val ob = overlayBuild(heroId, b, title, flag)
        load(ob) { g.heroes.recordBuild(heroId, b, ob.pieces.filter { it.reforgeStep }.map { it.item.id }, rolledMods()) }
    }

    /** Loads the pool's moves as the in-game checklist; finishing it records them all. */
    fun loadPool(): Boolean {
        val p = g.pool.state ?: return false
        val b = poolOverlay() ?: return false
        load(b) {
            g.pool.equip(p, b.pieces.filter { it.reforgeStep }.map { it.item.id }, rolledMods())
            g.nav.tab(Screen.Optimize)
        }
        return true
    }

    fun poolOverlay(): OverlayBuild? {
        val p = g.pool.state ?: return null
        val steps =
            p.builds.flatMap { (id, b) ->
                val name = g.heroes.heroName(id) ?: return@flatMap emptyList()
                val ob = b?.let { overlayBuild(id, it, name) } ?: return@flatMap emptyList()
                ob.pieces.filter { !it.onHero || it.otherMenu }.map { it.copyFor(name) }
            }
        val pieces = steps.filter { it.menu == 0 } + steps.filter { it.menu == 1 } + steps.filter { it.menu == 2 }
        return OverlayBuild("Pool · ${pieces.size} moves", null, pieces, emptyList(), p.takeIf { it.fromHistory }?.let { g.history.flag(it.startedAt) })
    }

    fun overlayBuild(heroId: Long, b: Build, title: String, flag: String? = null): OverlayBuild {
        val h = g.heroes.hero(heroId)
        val reforged = g.heroes.request(heroId).reforged
        val cur = g.heroes.currentBuild(heroId)
        val items = Slot.entries.mapNotNull { s -> b.items.firstOrNull { it.slot == s } }.filter { !it.ghost }

        // a piece the build counts as reforged that isn't yet: equipped as it is, then reforged in its own step
        fun needsReforge(it: Item) = reforged && it.reforgeable && box?.canReforge(it.id) == true

        fun step(it: Item, modStep: Boolean, reforgeStep: Boolean = false): OverlayPiece {
            val shown = reforged && it.reforgeable && (reforgeStep || modStep || !needsReforge(it))
            val main = if (shown) Reforge.main(it.mainType, it.mainValue) else it.mainValue
            val holder = it.equippedOn
            val set = content.set(it.set)
            return OverlayPiece(
                it, set?.icon, "${it.mainType.short} ${it.mainType.format(main)}",
                // the piece as it looks in the game today; a mod is shown as its own step
                (box?.itemsById?.get(it.id) ?: it).let { orig ->
                    orig.subs.indices.map { i ->
                        orig.subs[i].let { s -> (if (s.modified) "改 " else "") + "${s.type.short} ${s.type.format(Calc.subValue(orig, i, shown))}" }
                    }
                },
                when (holder) {
                    null -> "in bag"
                    heroId -> "on now"
                    else -> "on " + (g.heroes.heroName(holder) ?: "other")
                },
                holder == heroId,
                it.mod?.takeIf { modStep }?.let { m -> "${m.from.short} ${m.from.format(m.fromValue)} → ${m.to.short} ${m.to.format(m.toValue)}" },
                box?.itemsById?.get(it.id) ?: it, shown, reforgeStep = reforgeStep,
            )
        }
        val pieces = items.map { step(it, false) } + items.filter(::needsReforge).map { step(it, false, true) } + items.filter { it.mod != null }.map { step(it, true) }
        val stats = Stat.entries.map { st -> Triple(st, b.final[st], cur?.final?.get(st) ?: 0.0) }
        return OverlayBuild(title, h?.let(g.heroes::face), pieces, stats, flag)
    }

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
