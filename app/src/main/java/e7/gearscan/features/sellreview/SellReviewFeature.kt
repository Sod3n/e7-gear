package e7.gearscan.features.sellreview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.state.FeatureContext
import e7.gearscan.features.content.ContentFeature
import e7.gearscan.features.scan.ScanFeature
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.features.records.RecordsFeature
import e7.gearscan.features.heroes.HeroesFeature
import e7.gearscan.features.pool.PoolFeature
import e7.gearscan.calc.Reforge
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Item
import e7.gearscan.data.SellQuery
import e7.gearscan.data.SellSort
import e7.gearscan.data.Slot
import e7.gearscan.opt.GearBounds
import e7.gearscan.opt.KeepCurve
import e7.gearscan.opt.SellSearch
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.Plan
import e7.gearscan.state.Weight
import e7.gearscan.opt.GearScore
import e7.gearscan.ui.Confirmed
import e7.gearscan.ui.Outcome
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SellReviewFeature(ctx: FeatureContext, private val content: ContentFeature, private val scanFeature: ScanFeature, private val marks: MarksFeature, private val recordsFeature: RecordsFeature, private val heroes: HeroesFeature, private val pool: PoolFeature) : Feature(ctx, "sellReview") {
    var query by saved("query", SellQuery(), Codecs.obj({ it.toJson() }, { SellQuery.of(it) }))
        private set
    val progress = savedFile("sell_progress.jsonl", backup = false, path = "sell_progress.jsonl")
    val run = work<Unit>("Sell review", Weight.Heavy, notice = "Checking sell filters")
    val curve = work<Unit>("Keep curve")

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

    private fun settingsKey(): List<Any?> = listOf(pool.tiers, pool.extra, heroes.tiers, content.content.version, ctx.rev.value)

    private fun freshnessKey(): List<Any?> =
        listOf(
            scanFeature.box,
            scanFeature.lastScan,
            recordsFeature.recordedSteps,
            settingsKey(),
            query.copy(rollOn = 0.0),
        )

    val stale get() = result != null && appliedKey != freshnessKey()
    val fresh get() = result != null && !stale && !busy

    fun edit(q: SellQuery) {
        if (q.copy(rollOn = 0.0) == query.copy(rollOn = 0.0)) {
            query = q
            ctx.store.saveSoon()
            return
        }
        keepCurve = null
        curve.cancel()
        stop()
        query = if (!q.scoreFilter && q.sort in listOf(SellSort.SCORE, SellSort.CHANCE)) q.copy(sort = SellSort.QUALITY) else q
        ctx.rev.bump()
    }

    fun resetSettings(confirmed: Confirmed) = edit(SellQuery())

    fun resetScoreModel(confirmed: Confirmed) = edit(query.copy(score = GearScore.Model()))

    fun resetEsBars(confirmed: Confirmed) = edit(query.copy(esBars = emptyMap()))

    fun resetSafeguards(confirmed: Confirmed) = edit(query.copy(keepStats = SellQuery.DEFAULT_GUARDS, keepChance = 1.0))

    fun stop() = run.cancel()

    fun find(): Outcome {
        stop()
        val q = query
        q.error()?.let { return Outcome.Failed(it) }
        val b = scanFeature.box ?: return Outcome.Done
        val text = b.toText()
        val scan = scanFeature.lastScan
        val records = recordsFeature.recordedSteps
        val removed = marks.reviewed
        val members = pool.members().map { it.id }.toSet()
        val names = b.allHeroes.associate { it.id to heroes.heroName(it) }
        val version = content.content.version
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
                ctx.messages.showToast("The check stopped. Completed pieces are saved; tap Find matches to continue.")
            }
        }
    }

    fun toTrack(id: Long? = null): Plan<Pair<List<Pair<Item, SellSearch.Row>>, SellQuery>> {
        val found = result ?: return Plan.Stop(Outcome.Done)
        if (!fresh) return Plan.Stop(Outcome.Failed("Update the matches before opening this list."))
        val b = scanFeature.box ?: return Plan.Stop(Outcome.Done)
        val reviewed = marks.reviewed
        val rows =
            found.matches.filter { id == null || it.item.id == id }
                .mapNotNull { row -> b.itemsById[row.item.id]?.takeIf { it.id !in reviewed }?.let { it to row } }
        if (rows.isEmpty()) return Plan.Stop(Outcome.Failed("No matching pieces remain. Update the search."))
        return Plan.Go(rows to found.query)
    }

    override fun onBoxReplacing() {
        reset()
    }

    fun reset() {
        stop()
        result = null
        keepCurve = null
        appliedKey = null
    }

    fun context(q: SellQuery): SellSearch.Context? {
        val b = scanFeature.box ?: return null
        val key = listOf(b, scanFeature.lastScan, recordsFeature.records, marks.reviewed, settingsKey(), q)
        if (key != contextKey) {
            cachedContext =
                SellSearch.Context(
                    b,
                    recordsFeature.recordedSteps,
                    scanFeature.lastScan,
                    marks.reviewed,
                    pool.members().map { it.id }.toSet(),
                    b.allHeroes.associate { it.id to heroes.heroName(it) },
                    q,
                    content.content.version,
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
                        scanFeature.box?.items.orEmpty().filter { it.mainType == t }
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
        val b = scanFeature.box ?: return
        val ctx = context(found.query) ?: return
        val reviewed = marks.reviewed
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
