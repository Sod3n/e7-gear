package e7.gearscan.features.sellreview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.calc.Reforge
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.SellSort
import e7.gearscan.data.Slot
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.opt.GearBounds
import e7.gearscan.opt.KeepCurve
import e7.gearscan.opt.SellSearch
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Weight
import e7.gearscan.ui.Outcome
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SellHost {
    val records: Map<Long, UpgradeRecord>
    val settingsKey: List<Any?>
    val contentVersion: String

    fun poolMembers(): Set<Long>

    fun heroNames(box: Box): Map<Long, String>

    fun startQueue(rows: List<Pair<Item, SellSearch.Row>>, query: SellQuery, openOverlay: Boolean)

    fun rollOnChanged(rollOn: Double)

    fun touch()
}

object NoSellHost : SellHost {
    override val records = emptyMap<Long, UpgradeRecord>()
    override val settingsKey = emptyList<Any?>()
    override val contentVersion = ""

    override fun poolMembers() = emptySet<Long>()

    override fun heroNames(box: Box) = emptyMap<Long, String>()

    override fun startQueue(rows: List<Pair<Item, SellSearch.Row>>, query: SellQuery, openOverlay: Boolean) {}

    override fun rollOnChanged(rollOn: Double) {}

    override fun touch() {}
}

class SellReviewFeature(g: AppGraph) : Feature(g, "sellReview") {
    var query by saved("query", SellQuery(), Codecs.obj({ it.toJson() }, { SellQuery.of(it) }))
        private set
    val progress = savedFile("sell_progress.jsonl", backup = false, path = "sell_progress.jsonl")
    val run = work<Unit>("Sell review", Weight.Heavy, notice = "Checking sell filters")
    val curve = work<Unit>("Keep curve")

    // the tracker, pool members and hero names still live in AppModel; this goes when their features own them
    var host: SellHost = NoSellHost

    var result: SellSearch.Result? by mutableStateOf(null)
        private set
    var keepCurve: KeepCurve.Result? by mutableStateOf(null)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    val busy get() = run.busy
    val curveBusy get() = curve.busy

    private var appliedKey: List<Any?>? = null
    private val mutex = Mutex()
    private var contextKey: List<Any?>? = null
    private var cachedContext: SellSearch.Context? = null

    private val log =
        object : SellSearch.Log {
            override fun lines() = progress.read()?.let { StringReader(it).readLines() }

            override fun replace(text: String) = progress.write(text)

            override fun append(text: String) = progress.append(text)
        }

    private fun freshnessKey(): List<Any?> =
        listOf(
            g.scan.box,
            g.scan.lastScan,
            host.records.filterValues { it.scan == g.scan.lastScan && it.history.size > 1 },
            host.settingsKey,
            query.copy(rollOn = 0.0),
        )

    val stale get() = result != null && appliedKey != freshnessKey()
    val fresh get() = result != null && !stale && !busy

    fun edit(q: SellQuery) {
        if (q.copy(rollOn = 0.0) == query.copy(rollOn = 0.0)) {
            query = q
            host.rollOnChanged(q.rollOn)
            g.store.saveSoon()
            return
        }
        keepCurve = null
        curve.cancel()
        stop()
        query = if (!q.scoreFilter && q.sort in listOf(SellSort.SCORE, SellSort.CHANCE)) q.copy(sort = SellSort.QUALITY) else q
        host.touch()
    }

    fun stop() = run.cancel()

    fun find(): Outcome {
        stop()
        val q = query
        q.error()?.let { return Outcome.Failed(it) }
        val b = g.scan.box ?: return Outcome.Done
        val text = b.toText()
        val scan = g.scan.lastScan
        val records = host.records.filterValues { it.scan == scan && it.history.size > 1 }
        val removed = g.marks.reviewed
        val members = host.poolMembers()
        val names = host.heroNames(b)
        val version = host.contentVersion
        val key = freshnessKey()
        return run.start {
            val scope = this
            done = 0
            total = 0
            try {
                val found =
                    mutex.withLock {
                        withContext(Dispatchers.Default) {
                            val context = SellSearch.Context(Box.parse(text), records, scan, removed, members, names, q, version)
                            SellSearch.run(context, log, cancelled = { !scope.isActive }) { d, t ->
                                if (d % 20 == 0 || d == t) {
                                    runBlocking(Dispatchers.Main) {
                                        if (scope.isActive) {
                                            done = d
                                            total = t
                                            scope.progress(d, t, "$d/$t pieces")
                                        }
                                    }
                                }
                            }
                        }
                    }
                result = found
                appliedKey = key
                computeCurve(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                g.messages.showToast("The check stopped. Completed pieces are saved; tap Find matches to continue.")
            }
        }
    }

    fun trackMatches(id: Long? = null, openOverlay: Boolean = true): Outcome {
        val found = result ?: return Outcome.Done
        if (!fresh) return Outcome.Failed("Update the matches before opening this list.")
        val b = g.scan.box ?: return Outcome.Done
        val reviewed = g.marks.reviewed
        val rows =
            found.matches.filter { id == null || it.item.id == id }
                .mapNotNull { row -> b.itemsById[row.item.id]?.takeIf { it.id !in reviewed }?.let { it to row } }
        if (rows.isEmpty()) return Outcome.Failed("No matching pieces remain. Update the search.")
        host.startQueue(rows, found.query, openOverlay)
        return Outcome.Done
    }

    fun reset() {
        stop()
        result = null
        keepCurve = null
        appliedKey = null
    }

    fun context(q: SellQuery): SellSearch.Context? {
        val b = g.scan.box ?: return null
        val key = listOf(b, g.scan.lastScan, host.records, g.marks.reviewed, host.settingsKey, q)
        if (key != contextKey) {
            cachedContext =
                SellSearch.Context(
                    b,
                    host.records.filterValues { it.scan == g.scan.lastScan && it.history.size > 1 },
                    g.scan.lastScan,
                    g.marks.reviewed,
                    host.poolMembers(),
                    host.heroNames(b),
                    q,
                    host.contentVersion,
                )
            contextKey = key
        }
        return cachedContext
    }

    val scoreLimit: Double
        get() {
            val m = query.score
            val main =
                GearStat.entries.maxOf { t ->
                    val observed =
                        g.scan.box?.items.orEmpty().filter { it.mainType == t }
                            .maxOfOrNull { it.main15.takeIf(Double::isFinite) ?: it.mainValue } ?: 0.0
                    maxOf(observed, Reforge.main(t, 0.0)) * m.weight(t) * m.mainPercent / 100
                }
            val subs = GearStat.entries.sumOf { GearBounds.target(it, true, true).maximum * m.weight(it) }
            return maxOf(100.0, kotlin.math.ceil((main + subs) * 2), query.floors.values.maxOrNull() ?: 0.0, query.maximumCurrentScore ?: 0.0)
        }

    fun autoEsBars(): Map<Slot, Int> = context(query)?.autoEsBars.orEmpty()

    fun floorPreview(slot: Slot): SellSearch.Floor? {
        if (query.matchMain) return null
        val ctx = context(query.copy(floorPercent = 100.0, floors = emptyMap())) ?: return null
        val item = ctx.items.firstOrNull { it.slot == slot } ?: return null
        return ctx.floor(item)
    }

    private fun computeCurve(found: SellSearch.Result) {
        val b = g.scan.box ?: return
        val ctx = context(found.query) ?: return
        val reviewed = g.marks.reviewed
        val items = found.matches.filter { it.item.enhance < 15 && it.item.id !in reviewed }.map { it.item }
        val bars = Slot.entries.mapNotNull { s -> ctx.esBar(s)?.let { s to it } }.toMap()
        val reforge = found.query.reforge
        keepCurve = null
        curve.start {
            val c = withContext(Dispatchers.Default) { runCatching { KeepCurve.run(items, bars, { reforge && b.canReforge(it.id) }) }.getOrNull() }
            if (result === found) keepCurve = c
        }
    }
}
