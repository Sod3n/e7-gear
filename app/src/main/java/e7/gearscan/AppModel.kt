package e7.gearscan

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import e7.gearscan.calc.Rating
import e7.gearscan.data.Box
import e7.gearscan.data.Content
import e7.gearscan.data.GameHero
import e7.gearscan.data.Goal
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Item
import e7.gearscan.data.OwnedHero
import e7.gearscan.data.PresetTabs
import e7.gearscan.data.Presets
import e7.gearscan.data.Request
import e7.gearscan.data.SavedBuild
import e7.gearscan.data.ScanDiff
import e7.gearscan.data.Reports
import e7.gearscan.data.Slot
import e7.gearscan.opt.Build
import e7.gearscan.opt.PoolEffort
import e7.gearscan.opt.PoolLosses
import e7.gearscan.opt.Ghost
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.Setup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

sealed class Screen {
    data object ScanIntro : Screen()
    data object ScanData : Screen()
    data object Capture : Screen()
    data object ScanResult : Screen()
    data object Optimize : Screen()
    data object Heroes : Screen()
    data object Results : Screen()
    data object GhostBuild : Screen()
    data class Compare(val index: Int, val poolHero: Long? = null) : Screen()
    data class HeroDetail(val id: Long) : Screen()
    data class DiffItems(val kind: Int) : Screen()
    data object OverlayIntro : Screen()
    data class Feedback(val error: String? = null) : Screen()
    data object PoolRun : Screen()
    /** A pool run's results walked hero by hero, with reviewed marks. */
    data class PoolReview(val id: Long) : Screen()
    /** One pool hero's full setup; [setup]: walk the heroes with no build, else the ready ones. */
    data class PoolHero(val id: Long, val setup: Boolean) : Screen()
}

enum class SheetKind { PRESET, TARGETS, ADVANCED, BONUS, SETS, MAINS, GOALS, SHOW, HERO_PICK, ARTIFACT, POOL_PICK, POOL_WORSE, POOL_LOW, POOL_HEROES, POOL_LIST, POOL_TRADES, SCAN_RESTART }

/** [back] is the sheet to return to when this one finishes; null closes. */
data class Sheet(val kind: SheetKind, val heroId: Long, val back: SheetKind? = null)

enum class ScanError { NOTHING, SERVER, VPN, NOT_INSTALLED }

enum class ScanPhase { IDLE, CAPTURING, IMPORTING }

class SearchState(
    val heroId: Long,
    val running: Boolean = true,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    val builds: List<Build> = emptyList(),
    val current: Build? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val ghostRunning: Boolean = false,
    val ghost: Build? = null,
    val ghostSearched: Boolean = false,
    val team: List<Pair<Long, Build?>> = emptyList(),
    val moved: Int = 0,
    /** Opened from history (its start time): read only, nothing to equip from here. */
    val historyAt: Long = 0,
)

const val CRASH_FILE = "crash.txt"

/** Builds a one-hero search keeps in history: the top of the list, not all fifty. */
const val HISTORY_BUILDS = 10

/** Time cap per hero in a pool run; top-1 searches usually finish far sooner. */
const val POOL_HERO_MS = 6_000L

/** Sheet hero id for the Advanced sheet editing every hero in the pool run at once. */
const val POOL_RULES = -2L

/**
 * Pieces [taker] wanted from [holder], both in the run: [made] if they moved. A trade not made carries what it would have
 * done in match points ([gain] for the taker, [loss] for the holder); a made one reads its effect off the run's before/after.
 */
class Trade(val taker: Long, val holder: Long, val items: List<Long>, val made: Boolean,
            val takerPoints: Pair<Double, Double> = e7.gearscan.opt.NaN2, val holderPoints: Pair<Double, Double> = e7.gearscan.opt.NaN2, val moves: Int = 0,
            /** The player gave these pieces away, but the wearer has no full build at all without them. */
            val stuck: Boolean = false)

data class PoolState(
    val ids: List<Long>,
    val before: Map<Long, Int?>,
    val builds: Map<Long, Build?>,
    val after: Map<Long, Int?>,
    val current: Long?,
    val currentBest: Int?,
    val running: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    /** Pieces one hero of the run wanted from another, made or not; the player may overrule each and re-run. */
    val trades: List<Trade> = emptyList(),
    /** Pass over the heroes; a later pass looks again at what earlier trades freed up. */
    val round: Int = 1,
    /** Heroes looked at in the results walk-through; kept with the run's history entry. */
    val reviewed: Set<Long> = emptySet(),
    /** Opened from history: read only, the gear may have moved since. */
    val fromHistory: Boolean = false,
    /** Each hero's setup as the run used it, so an edit made while reviewing can offer a re-run. */
    val setups: Map<Long, String> = emptyMap(),
) {
    val moves get() = builds.entries.sumOf { (id, b) -> b?.items?.count { it.equippedOn != id && !it.ghost } ?: 0 }
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val files = app.filesDir
    private val boxFile = File(files, "box.json")
    private val stateFile = File(files, "state.json")
    private val historyFile = File(files, "history.json")
    internal val buildsDir = File(files, "builds")
    val limitMs = 15_000L

    var content: Content by mutableStateOf(loadContent())
        private set
    var box: Box? by mutableStateOf(null)
        private set
    /** Bumped on every in-place change to the box or configs, so readers recompose. */
    var rev by mutableIntStateOf(0)
        private set
    private val configs = HashMap<Long, HeroConfig>()
    /** Gear tier per hero, 1 (just geared) to 5 (best of account); unset means 1. */
    var tiers: Map<Long, Int> by mutableStateOf(emptyMap())
    var team: List<Long> by mutableStateOf(emptyList())
    var consent by mutableStateOf(false)
    var proxyOn by mutableStateOf(false)
    var proxy by mutableStateOf("")
    var lastScan by mutableStateOf(0L)
    var lastDiff: ScanDiff? by mutableStateOf(null)
    var decodeSec by mutableIntStateOf(0)
    var showRating by mutableStateOf(Rating.DMG)
    /** With the "closest to targets" goal, results show match % unless another rating is picked. */
    var showMatch by mutableStateOf(true)

    var stack: List<Screen> by mutableStateOf(listOf(Screen.Optimize))
        internal set
    var sheet: Sheet? by mutableStateOf(null)

    val presets = mutableStateMapOf<String, PresetTabs?>()
    val presetLoading = mutableStateMapOf<String, Boolean>()

    var search: SearchState? by mutableStateOf(null)
        internal set
    private var run: SearchRun? = null

    var scanPhase by mutableStateOf(ScanPhase.IDLE)
    internal var captureStarted = 0L
    var scanError: ScanError? by mutableStateOf(null)
    var captureConns by mutableIntStateOf(0)
    var captureBytes by mutableStateOf(0L)
    var returnedEarly by mutableStateOf(false)

    var lastError: String? by mutableStateOf(null)
    var overlaySeen by mutableStateOf(false)
    private val reportsDir = File(files, "reports")

    val game: String? = app.packageManager.getInstalledApplications(0).map { it.packageName }
        .firstOrNull { it.lowercase().let { p -> "epic7" in p || "epicseven" in p } }

    var ready by mutableStateOf(false)
        private set

    // ---- navigation ----
    val screen get() = stack.last()
    /** The sheet open on a screen when another screen was opened over it, by stack depth; back reopens it. */
    private val sheetUnder = HashMap<Int, Pair<Screen, Sheet>>()

    fun go(s: Screen) {
        sheet?.let { sheetUnder[stack.lastIndex] = stack.last() to it }
        sheet = null
        stack = stack + s
    }
    // replacing the only screen keeps Optimize under it, so the new screen's back link has somewhere to go
    fun replace(s: Screen) { sheet = null; stack = stack.dropLast(1).ifEmpty { if (s == Screen.Optimize) emptyList() else listOf(Screen.Optimize) } + s }
    fun tab(s: Screen) { sheetUnder.clear(); sheet = null; stack = listOf(s) }
    fun back(): Boolean {
        if (sheet != null) { sheet = null; return true }
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        sheetUnder.remove(stack.lastIndex)?.takeIf { it.first == stack.last() }?.let { sheet = it.second }
        return true
    }

    // ---- content ----
    private fun loadContent(): Content {
        // the APK carries no game data: the last download, or nothing until the first one arrives
        val downloaded = runCatching { Content.parse(File(getApplication<Application>().filesDir, "content.json").readText()) }.getOrNull()
        return (downloaded?.takeIf { it.schema <= e7.gearscan.data.ContentUpdate.SCHEMA } ?: Content.EMPTY)
            .also { e7.gearscan.ui.GameImages.base = it.imageBase }
    }

    /** A newer app version on GitHub Releases, found by the daily check. */
    var appUpdate: e7.gearscan.data.AppUpdate.Release? by mutableStateOf(null)
        internal set

    val appVersion: String get() = getApplication<Application>().let { it.packageManager.getPackageInfo(it.packageName, 0).versionName ?: "0" }

    /** Sideloaded installs hear about new versions only from here; quiet on any failure. */
    private fun checkAppUpdate() {
        viewModelScope.launch {
            appUpdate = withContext(Dispatchers.IO) {
                runCatching {
                    val c = e7.gearscan.data.Http.open(e7.gearscan.data.AppUpdate.LATEST) as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 15000; c.setRequestProperty("Accept", "application/vnd.github+json")
                    if (c.responseCode != 200) null else e7.gearscan.data.AppUpdate.newer(appVersion, c.inputStream.bufferedReader().use { it.readText() })
                }.getOrNull()
            }
        }
    }

    /** The self-update's download, 0..1; null when none is running. */
    var updateProgress: Float? by mutableStateOf(null)
        private set
    var updateProblem: String? by mutableStateOf(null)
        private set
    /** Downloaded and checked, waiting for the player to allow installs from this app. */
    private var installWhenAllowed: File? = null

    /** Downloads [appUpdate], checks it is this app signed with this key and newer, then opens Android's installer. */
    fun updateApp() {
        val u = appUpdate ?: return
        val app = getApplication<Application>()
        if (u.apk == null) {
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.page)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (updateProgress != null) return
        updateProblem = null
        updateProgress = 0f
        viewModelScope.launch {
            val apk = AppInstall.file(app, u.version)
            val problem = withContext(Dispatchers.IO) {
                runCatching {
                    if (!apk.exists()) AppInstall.download(u.apk, apk, u.size) { p -> if ((p * 100).toInt() != ((updateProgress ?: 0f) * 100).toInt()) updateProgress = p }
                    AppInstall.refusal(app, apk)
                }.getOrElse { if (it is java.io.IOException) "" else "the download failed (${it.message})" }
            }
            updateProgress = null
            when {
                problem != null -> { apk.delete(); updateProblem = if (problem.isEmpty()) "Download stopped. Tap to try again." else "Not installed: $problem." }
                app.packageManager.canRequestPackageInstalls() -> AppInstall.install(app, apk)
                else -> {
                    installWhenAllowed = apk
                    updateProblem = "Allow installs from E7 Gear, then come back."
                    app.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${app.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    /** Back from the "Install unknown apps" switch: installs the waiting update once it's allowed. */
    fun resumed() {
        val apk = installWhenAllowed ?: return
        val app = getApplication<Application>()
        if (!app.packageManager.canRequestPackageInstalls()) return
        installWhenAllowed = null
        updateProblem = null
        AppInstall.install(app, apk)
    }

    /** Last content check and what it found, for Scan & data. */
    var contentCheckedAt by mutableStateOf(0L)
    var contentStatus: String? by mutableStateOf(null)
        private set

    /** Fetches newer content from the content site; [quiet] for the daily check, which shows nothing unless it updates. */
    fun checkContent(quiet: Boolean = false) {
        val current = content
        contentStatus = if (quiet) contentStatus else "Checking…"
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    e7.gearscan.data.ContentUpdate.check(current) { site ->
                        e7.gearscan.data.Mirror.fetch(site, siteFirst = true) { url ->
                            val c = e7.gearscan.data.Http.open(url) as java.net.HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 20000; c.useCaches = false
                            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
                            c.inputStream.use { it.readBytes() }
                        }
                    }.also { o ->
                        // whole-file replace, so a write cut short never leaves half a content file
                        o.bytes?.let { b -> File(files, "content.json.tmp").apply { writeBytes(b); renameTo(File(files, "content.json")) } }
                    }
                }
            }
            contentCheckedAt = System.currentTimeMillis()
            out.onSuccess { o ->
                contentStatus = when (o.result) {
                    e7.gearscan.data.ContentUpdate.Result.UPDATED -> {
                        content = o.content!!; e7.gearscan.ui.GameImages.base = content.imageBase; touch(); "Updated to ${o.version}"
                    }
                    e7.gearscan.data.ContentUpdate.Result.NEEDS_APP -> "Content ${o.version} needs a newer app"
                    e7.gearscan.data.ContentUpdate.Result.CURRENT -> if (quiet) contentStatus else "Up to date"
                }
            }.onFailure { if (!quiet) contentStatus = "Couldn't check: ${it.message?.take(80)}" }
            save()
        }
    }

    // every change is saved shortly after: Android may close the app while the player is in the game
    fun touch() { rev++; saveSoon() }

    private var pendingSave: kotlinx.coroutines.Job? = null
    private fun saveSoon() {
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch { delay(500); save() }
    }

    // ---- heroes ----
    fun hero(id: Long): OwnedHero? = box?.hero(id)
    fun gameHero(h: OwnedHero): GameHero? = content.heroes[h.code]
    fun heroName(h: OwnedHero) = h.name.ifEmpty { content.heroes[h.code]?.name ?: h.code }
    fun face(h: OwnedHero) = content.face(h.code)
    fun config(id: Long): HeroConfig = configs.getOrPut(id) { HeroConfig() }

    fun request(id: Long): Request {
        val cfg = config(id)
        return cfg.request ?: Request().also { cfg.request = it }
    }

    fun setup(id: Long, excluded: Set<Long> = emptySet(), canTakeFrom: (Long) -> Boolean = { tier(it) < tier(id) }): Setup? {
        val b = box ?: return null
        val h = b.hero(id) ?: return null
        val g = gameHero(h) ?: return null
        return Setup(h, g, config(id), request(id), b, content, canTakeFrom, excluded)
    }

    fun tier(id: Long) = tiers[id] ?: 1

    fun setTier(id: Long, t: Int) { tiers = tiers + (id to t.coerceIn(1, 5)); save() }

    fun currentBuild(id: Long): Build? = setup(id)?.let { s -> s.build(s.current().values.toList()) }

    fun pickHero(id: Long) {
        team = listOf(id) + team.drop(1).filter { it != id }
        ensurePresets(id)
        save()
    }


    // ---- presets ----
    /** Heroes whose presets were loaded with the network allowed; a cache-only load (Heroes list, low tiers) misses RTA files never fetched. */
    internal val presetsOnline = mutableSetOf<String>()

    fun ensurePresets(id: Long, force: Boolean = false) {
        val h = hero(id) ?: return
        if (presetLoading[h.code] == true) return
        if (!force && presets.containsKey(h.code) && h.code in presetsOnline) { autoPreset(id); return }
        presetLoading[h.code] = true
        viewModelScope.launch {
            val tabs = withContext(Dispatchers.IO) {
                Presets.load(buildsDir, content, h.code, heroName(h), allowNetwork = true)
            }
            presets[h.code] = tabs
            presetsOnline += h.code
            presetLoading[h.code] = false
            autoPreset(id)
        }
    }

    /** Loads a hero's community builds for the match % without applying one; network only when asked. */
    fun loadPresetsQuietly(id: Long, network: Boolean) {
        val h = hero(id) ?: return
        if (presetLoading[h.code] == true) return
        if (presets.containsKey(h.code) && (!network || h.code in presetsOnline)) return
        presetLoading[h.code] = true
        viewModelScope.launch {
            val tabs = withContext(Dispatchers.IO) { Presets.load(buildsDir, content, h.code, heroName(h), allowNetwork = network) }
            presets[h.code] = tabs
            if (network) presetsOnline += h.code
            presetLoading[h.code] = false
        }
    }

    private fun missingSets(b: Build, req: Request) =
        e7.gearscan.data.Match.missingSets(b.items.groupingBy { it.set }.eachCount(), req.setAlts) { content.set(it) }

    /** 0% of match: mains and sets with no substats, so the % is what substats have closed toward the targets. */
    private fun mainsOnly(id: Long, req: Request): ((e7.gearscan.data.Stat) -> Double)? {
        val s = setup(id) ?: return null
        val f = e7.gearscan.calc.Calc.final(s.hb, e7.gearscan.opt.Ghost.mainsOnly(req), req.reforged, content)
        return { f[it] }
    }

    /** Roll sizes the targets goal scores shortfall in, so match % orders builds exactly as the search does. */
    private fun rolls(id: Long): ((e7.gearscan.data.Stat) -> Double)? {
        val s = setup(id) ?: return null
        val r = e7.gearscan.opt.Objectives.rollSize(s.hb)
        return { r[it.ordinal] }
    }

    /** Match with the hero's own targets when it has some (usually from its preset), else with the most used build. */
    fun matchFor(id: Long): e7.gearscan.data.Match.Result? {
        val h = hero(id) ?: return null
        val cur = currentBuild(id) ?: return null
        val req = config(id).request?.takeIf { r -> r.targets.any { !it.isNaN() } }
        if (req != null) return e7.gearscan.data.Match.of({ cur.final[it] }, mainsOnly(id, req) ?: return null, req.targets, req.weights,
            req.presetLabel?.substringAfter("· ") ?: "your targets", rolls(id) ?: { 1.0 }, missingSets(cur, req))
        val p = presets[h.code]?.mostUsed?.firstOrNull() ?: return null
        return matchFor(id, p)
    }

    /** What applying [p] would ask for, without applying it. */
    fun presetRequest(id: Long, p: e7.gearscan.data.Preset): Request? {
        val g = hero(id)?.let { gameHero(it) } ?: return null
        val cfg = config(id)
        return Presets.toRequest(p, e7.gearscan.calc.Calc.heroBase(g, cfg, cfg.artifact ?: p.artifact, content), null)
    }

    fun matchFor(id: Long, p: e7.gearscan.data.Preset): e7.gearscan.data.Match.Result? {
        val cur = currentBuild(id) ?: return null
        val r = presetRequest(id, p) ?: return null
        return e7.gearscan.data.Match.of({ cur.final[it] }, mainsOnly(id, r) ?: return null, r.targets, r.weights, p.label, rolls(id) ?: { 1.0 },
            missingSets(cur, r))
    }

    private fun autoPreset(id: Long) {
        val cfg = config(id)
        val h = hero(id) ?: return
        cfg.request?.let { r -> if (!r.isBlank()) return }
        val t = presets[h.code] ?: return
        // the build closest to the gear already worn; most used first so ties keep the common build
        val all = listOf("Most used" to t.mostUsed, "RTA" to t.rta, "Top ranked" to t.topRanked, "Fastest" to t.fastest)
            .flatMap { (tab, ps) -> ps.map { tab to it } }
        val (tab, p) = all.maxByOrNull { (_, p) -> matchFor(id, p)?.pct ?: -1 } ?: return
        applyPreset(id, p, tab, auto = true)
    }

    fun applyPreset(id: Long, p: e7.gearscan.data.Preset, tab: String = "Most used", auto: Boolean = false) {
        val h = hero(id) ?: return
        val g = gameHero(h) ?: return
        val cfg = config(id)
        val hb = e7.gearscan.calc.Calc.heroBase(g, cfg, cfg.artifact ?: p.artifact, content)
        cfg.request = Presets.toRequest(p, hb, cfg.request, tab, auto)
        touch(); save()
    }

    // ---- search ----
    fun startSearch() {
        cancelSearch()
        val id = team.firstOrNull() ?: return
        val setup = setup(id) ?: return
        val cur = setup.build(setup.current().values.toList())
        showRating = when (setup.req.goal) {
            Goal.EHP -> Rating.EHP; Goal.SPEED -> Rating.SPD; Goal.CP -> Rating.CP; Goal.GEAR_SCORE -> Rating.GS
            else -> if (showRating in listOf(Rating.EHP, Rating.SPD, Rating.CP, Rating.GS)) Rating.DMG else showRating
        }
        val r = SearchRun(setup, 50)
        run = r
        search = SearchState(id, current = cur)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        thread(name = "search") {
            val t0 = System.nanoTime()
            r.run(threads, limitMs)
            val builds = r.builds()
            android.util.Log.i("E7Search", "threads=$threads ms=${(System.nanoTime() - t0) / 1_000_000} nodes=${r.engine.nodes.get()} " +
                "timedOut=${r.engine.timedOut} kept=${r.engine.keptPerSlot.toList()} best=${builds.firstOrNull()?.value}")
            val stopped = r.engine.cancelled
            viewModelScope.launch {
                if (run !== r) return@launch
                val started = search?.startedAt ?: return@launch
                // into history before the search reads as done, as with pool runs
                if (builds.isNotEmpty() && !stopped) {
                    val best = builds.first()
                    record(e7.gearscan.data.HistoryEntry(started, false, hero(id)?.let(::heroName) ?: "Hero",
                        setup.req.goal.short + (setup.req.presetLabel?.let { " · " + it.substringAfter(" · ") } ?: ""),
                        matchOf(id, cur)?.pct ?: 0, matchOf(id, best)?.pct ?: 0, best.items.count { !it.ghost && (it.equippedOn != id || it.mod != null) },
                        mapOf(id to builds.take(HISTORY_BUILDS).map { b -> b.items.filter { !it.ghost }.map(e7.gearscan.data.PieceRef::of) })))
                }
                search = SearchState(id, false, started, System.currentTimeMillis(), builds, cur, r.engine.timedOut, stopped,
                    ghostRunning = setup.req.ghost && builds.isNotEmpty() && !stopped)
                if (setup.req.ghost && builds.isNotEmpty() && !stopped) startGhost(setup, builds.first(), r)
            }
        }
        viewModelScope.launch {
            while (run === r && search?.running == true) {
                delay(300)
                if (run !== r || search?.running != true) break
                val partial = withContext(Dispatchers.Default) { r.builds() }
                if (run === r && search?.running == true) search = search?.let {
                    SearchState(id, true, it.startedAt, builds = partial, current = cur)
                }
            }
        }
    }

    private fun startGhost(setup: Setup, best: Build, main: SearchRun) {
        thread(name = "ghost") {
            val ghosts = Ghost.candidates(setup, best, setup.req.reforged)
            val g = SearchRun(setup, 1, ghosts, maxGhost = 1, startThreshold = best.value)
            g.run(Runtime.getRuntime().availableProcessors().coerceIn(1, 6), 10_000)
            val b = g.builds().firstOrNull()?.takeIf { it.ghost != null }
            viewModelScope.launch {
                if (run !== main) return@launch
                search = search?.let {
                    SearchState(it.heroId, false, it.startedAt, it.finishedAt, it.builds, it.current, it.timedOut, it.cancelled,
                        ghostRunning = false, ghost = b, ghostSearched = true)
                }
            }
        }
    }

    fun cancelSearch() {
        run?.cancel()
        search?.let { if (it.running) search = SearchState(it.heroId, false, it.startedAt, System.currentTimeMillis(), it.builds, it.current, cancelled = true, team = it.team) }
    }

    // ---- build actions ----
    /** Equips in the app's box; returns every touched item's previous owner, for [undoEquip]. */
    fun equip(heroId: Long, build: Build): Map<Long, Long?> {
        val b = box ?: return emptyMap()
        val prev = LinkedHashMap<Long, Long?>()
        for (it in build.items) {
            if (it.ghost) continue
            val item = b.itemsById[it.id] ?: it
            b.gearOf(heroId)[it.slot]?.takeIf { cur -> cur.id != it.id }?.let { cur -> prev.putIfAbsent(cur.id, cur.equippedOn); b.setEquipped(cur, null) }
            prev.putIfAbsent(item.id, item.equippedOn)
            b.setEquipped(item, heroId)
        }
        touch(); saveBox(); save()
        return prev
    }

    /** Equips [b] in the app's box and keeps it in the hero's saved builds; the toast offers Undo. */
    fun recordBuild(heroId: Long, b: Build) {
        val prev = equip(heroId, b)
        val name = request(heroId).presetLabel?.let { "${it.substringAfter("· ").replace("Community ", "Preset ")} build" } ?: "Build ${config(heroId).saved.size + 1}"
        if (config(heroId).saved.none { it.items.toSet() == b.items.filter { i -> !i.ghost }.map { i -> i.id }.toSet() }) saveBuild(heroId, b, name)
        showToast("Equipped in this app and saved to ${hero(heroId)?.let(::heroName) ?: "the hero"}'s builds", "Undo") { undoEquip(prev) }
    }

    fun undoEquip(prev: Map<Long, Long?>) {
        val b = box ?: return
        for ((id, owner) in prev) b.itemsById[id]?.let { b.setEquipped(it, owner) }
        touch(); saveBox(); save()
    }

    /** A short message over the bottom of the screen, with an optional action such as Undo. */
    class Toast(val text: String, val action: String?, val onAction: (() -> Unit)?) { val id = System.nanoTime() }
    var toast: Toast? by mutableStateOf(null)

    fun showToast(text: String, action: String? = null, onAction: (() -> Unit)? = null) { toast = Toast(text, action, onAction) }

    fun saveBuild(heroId: Long, build: Build, name: String) {
        val cfg = config(heroId)
        cfg.saved += SavedBuild(name, build.items.filter { !it.ghost }.map { it.id })
        touch(); save()
    }

    fun lockBuild(build: Build) {
        val b = box ?: return
        build.items.filter { !it.ghost }.forEach { b.setLocked(it, true) }
        touch(); saveBox()
    }

    fun savedItems(s: SavedBuild): List<Item> = s.items.mapNotNull { box?.itemsById?.get(it) }

    // ---- scan ----
    fun otherVpnActive(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } &&
            scanPhase != ScanPhase.CAPTURING
    }

    fun onVpnGranted() {
        val g = game ?: run { scanError = ScanError.NOT_INSTALLED; return }
        val app = getApplication<Application>()
        Capture.clear()
        Capture.clearLog()
        scanError = null
        returnedEarly = false
        app.startForegroundService(Intent(app, TProxyService::class.java)
            .setAction(TProxyService.ACTION_CONNECT)
            .putExtra(TProxyService.EXTRA_GAME, g)
            .putExtra(TProxyService.EXTRA_UPSTREAM, if (proxyOn) proxy.trim() else ""))
        scanPhase = ScanPhase.CAPTURING
        captureStarted = System.currentTimeMillis()
        if (screen != Screen.Capture) go(Screen.Capture)
        viewModelScope.launch {
            delay(800)
            app.packageManager.getLaunchIntentForPackage(g)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Set while the player is on the game's system page to stop it; the scan starts when they come back. */
    var scanAfterRestart = false
    /** "Not running · scan now" from the restart sheet: the screen under it starts the scan. */
    var scanNow by mutableStateOf(false)

    fun openGameSettings() {
        val app = getApplication<Application>()
        game?.let { g -> app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", g, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openGame() {
        val app = getApplication<Application>()
        game?.let { g -> app.packageManager.getLaunchIntentForPackage(g)?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Heuristic only: the lobby's login reply carries the whole box, well over 100 KB. */
    val lobbyLikely get() = captureBytes > TProxyService.LOBBY_KB * 1024

    fun stopAndImport() {
        val app = getApplication<Application>()
        if (scanPhase == ScanPhase.CAPTURING)
            app.startService(Intent(app, TProxyService::class.java).setAction(TProxyService.ACTION_DISCONNECT))
        scanPhase = ScanPhase.IMPORTING
        scanError = null
        val t0 = System.currentTimeMillis()
        thread(name = "import") {
            val result = runCatching {
                val segments = Capture.hexSegments()
                if (segments.isEmpty()) return@runCatching ScanError.NOTHING to null
                val resp = try { Fribbels.getItems(segments) } catch (e: IOException) { return@runCatching ScanError.SERVER to null }
                val r = Fribbels.convert(resp)
                if (r.items.length() == 0) return@runCatching ScanError.NOTHING to null
                null to JSONObject().put("items", r.items).put("heroes", r.heroes).toString()
            }.getOrElse { ScanError.NOTHING to null }
            viewModelScope.launch {
                scanPhase = ScanPhase.IDLE
                val (err, text) = result
                if (err != null || text == null) {
                    scanError = err ?: ScanError.NOTHING
                    lastError = "scan: ${scanError!!.name.lowercase()}"
                    saveScanLog()
                    return@launch
                }
                saveScanLog()
                Capture.clear()
                decodeSec = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(1)
                importBox(text)
                replace(Screen.ScanResult)
            }
        }
    }

    fun importBox(text: String) {
        val nb = Box.parse(text)
        lastDiff = ScanDiff.between(box, nb)
        box = nb
        lastScan = System.currentTimeMillis()
        if (team.isEmpty() || team.any { nb.hero(it) == null }) team = listOfNotNull(defaultHero(nb))
        tiers = tiers.filterKeys { nb.hero(it) != null }
        boxFile.writeText(text)
        touch(); save()
        team.firstOrNull()?.let { ensurePresets(it) }
    }

    private fun defaultHero(b: Box): Long? =
        b.heroes.maxWithOrNull(compareBy({ tier(it.id) }, { h -> b.items.filter { it.equippedOn == h.id }.sumOf { it.score } }))?.id

    // ---- whole pool ----
    var poolMode by mutableStateOf(false)
    /** Optimize's third switch position: past runs and searches instead of a setup. */
    var showHistory by mutableStateOf(false)
    var poolTiers by mutableStateOf(setOf(5, 4, 3))
    var poolExtra by mutableStateOf(emptySet<Long>())
    /** Heroes left out of this pool run (skipped in setup, or kept as is after review); their gear stays where it is. */
    var poolSkip by mutableStateOf(emptySet<Long>())
    /** The player's calls on contested pieces for re-runs: piece -> hero that keeps it, piece -> hero that gets it. */
    var poolKeep by mutableStateOf(emptyMap<Long, Long>())
    var poolGive by mutableStateOf(emptyMap<Long, Long>())
    var poolLosses by mutableStateOf(PoolLosses.SAFE)
    var poolEffort by mutableStateOf(PoolEffort.BALANCED)
    var pool: PoolState? by mutableStateOf(null)
        internal set
    var lastPool: Pair<Long, Int>? by mutableStateOf(null)
    @Volatile private var poolToken: Any? = null

    /** Run order: higher tiers first; inside a tier the hero furthest from their build picks first (name breaks ties). */
    fun poolHeroes(): List<OwnedHero> = poolMembers().filter { it.id !in poolSkip }

    /** The run's heroes including those kept as they are. */
    fun poolMembers(): List<OwnedHero> = box?.heroes.orEmpty()
        .filter { (tier(it.id) in poolTiers || it.id in poolExtra) && gameHero(it) != null }
        .sortedWith(compareByDescending<OwnedHero> { tier(it.id) }.thenBy { matchFor(it.id)?.pct ?: 101 }.thenBy { heroName(it) })

    /** null while its community builds are still loading. */
    fun hasBuild(id: Long): Boolean? {
        val h = hero(id) ?: return false
        if (config(id).request?.targets?.any { !it.isNaN() } == true) return true
        if (!presets.containsKey(h.code) || presetLoading[h.code] == true) return null
        val t = presets[h.code] ?: return false
        return t.mostUsed.isNotEmpty() || t.rta.isNotEmpty()
    }

    fun poolScore(matches: Map<Long, Int?>): Int {
        var sum = 0.0; var w = 0.0
        for ((id, m) in matches) { if (m == null) continue; val k = tier(id).toDouble(); sum += k * m; w += k }
        return if (w == 0.0) 0 else Math.round(sum / w * 100).toInt()
    }

    fun matchOf(id: Long, b: Build): e7.gearscan.data.Match.Result? {
        val req = config(id).request?.takeIf { r -> r.targets.any { !it.isNaN() } } ?: return null
        val base = mainsOnly(id, req) ?: return null
        return e7.gearscan.data.Match.of({ b.final[it] }, base, req.targets, req.weights, req.presetLabel?.substringAfter("· ") ?: "", rolls(id) ?: { 1.0 },
            missingSets(b, req))
    }

    /** Re-applies the hero's preset (community build or role) by its saved label, or null when it can't be found. */
    fun presetReset(id: Long): (() -> Unit)? {
        val label = config(id).request?.presetLabel ?: return null
        val tab = label.substringBefore(" · "); val name = label.substringAfter(" · ")
        if (tab == "Role") return e7.gearscan.data.Role.entries.firstOrNull { it.label == name }?.let { r -> { applyRole(id, r) } }
        val t = hero(id)?.let { presets[it.code] } ?: return null
        val p = t.names.indexOf(tab).takeIf { it >= 0 }?.let { i -> t.tab(i).firstOrNull { it.label == name } } ?: return null
        return { applyPreset(id, p, tab) }
    }

    fun applyRole(id: Long, role: e7.gearscan.data.Role) {
        val h = hero(id) ?: return
        val g = gameHero(h) ?: return
        val cfg = config(id)
        cfg.request = role.request(e7.gearscan.calc.Calc.heroBase(g, cfg, cfg.artifact, content), cfg.request)
        touch(); save()
    }

    /** Whether the player wants this trade's pieces to move: their call if they made one, else what the run did. */
    fun tradeMoves(t: Trade): Boolean = when {
        t.items.all { poolGive[it] == t.taker } -> true
        t.items.all { poolKeep[it] == t.holder } -> false
        else -> t.made
    }

    fun callTrade(t: Trade, moves: Boolean) {
        poolKeep = poolKeep - t.items.toSet(); poolGive = poolGive - t.items.toSet()
        // a call the same as what the run did is no call: the re-run stays free to change it
        if (moves != t.made) { if (moves) poolGive = poolGive + t.items.associateWith { t.taker } else poolKeep = poolKeep + t.items.associateWith { t.holder } }
        touch()
    }

    /** A fresh run forgets the player's calls on trades; [keepCalls] re-runs with them. */
    fun startPool(keepCalls: Boolean = false) {
        if (!keepCalls) { poolKeep = emptyMap(); poolGive = emptyMap() }
        // another run replaces the one waiting to be equipped
        pendingPool = null
        val lastTrades = pool?.trades.orEmpty()
        val heroes = poolHeroes().filter { hasBuild(it.id) == true }
        for (h in heroes) if (config(h.id).request?.targets?.any { !it.isNaN() } != true) {
            presets[h.code]?.let { t -> (t.mostUsed.firstOrNull() ?: t.rta.firstOrNull())?.let { applyPreset(h.id, it, auto = true) } }
        }
        val ids = heroes.map { it.id }
        val before = ids.associateWith { matchFor(it)?.pct }
        val token = Any(); poolToken = token
        pool = PoolState(ids, before, emptyMap(), emptyMap(), null, null, true, System.currentTimeMillis(), 0, setups = ids.associateWith { setupKey(it) })
        PoolService.start(getApplication()) { viewModelScope.launch { if (poolToken === token) stopPool() } }
        val inRun = ids.toSet()
        val keepGear = poolSkip.flatMap { worn(it) }.toSet()
        // builds holds every hero looked at so far: its new build, or null while it keeps its gear
        val builds = LinkedHashMap<Long, Build?>()
        val after = LinkedHashMap<Long, Int?>()
        var shown = 0L
        fun publish(round: Int? = null) {
            val snapB = LinkedHashMap(builds); val snapA = LinkedHashMap(after)
            viewModelScope.launch { if (poolToken === token) pool = pool?.copy(builds = snapB, after = snapA, round = round ?: pool?.round ?: 1) }
            // the system drops notification updates faster than about one a second
            if (System.currentTimeMillis() - shown > 1000 && poolToken === token) {
                shown = System.currentTimeMillis()
                PoolService.progress(getApplication(), (if ((round ?: 1) > 1) "Round $round · " else "") + "${snapB.size} of ${ids.size} heroes", snapB.size, ids.size)
            }
        }
        thread(name = "pool") {
            // heroes of the run may be asked for any piece, at a price; from heroes outside it, only lower tiers' pieces are free
            val pr = e7.gearscan.opt.pricedPool(ids, ::tier, keepGear, { id, excluded -> setup(id, excluded) { h -> h in inRun || tier(h) < tier(id) } },
                { s, r -> runWatched(s.hero.id, r, s.req.mods, token) }, poolLosses, poolEffort, { poolToken !== token },
                onVisit = { id, round -> if (id !in builds) builds[id] = null; publish(round) },
                onAccept = { assign -> changed(assign, builds, after, before); publish() }, keep = poolKeep, give = poolGive)
            val t0 = System.nanoTime()
            val result = pr.run()
            android.util.Log.i("E7Pool", "pool run: ${ids.size} heroes, %.1f s, ${pr.proposals} proposals, ".format(java.util.Locale.US, (System.nanoTime() - t0) / 1e9) +
                pr.spent.entries.joinToString { (k, v) -> "$k ${v.first}× %.1f s".format(java.util.Locale.US, v.second) } + "; slowest: " +
                pr.heroTime.entries.sortedByDescending { it.value.first }.take(6).joinToString { (h, v) -> "${hero(h)?.let(::heroName)} %.1f s".format(java.util.Locale.US, v.first) + if (v.second > 0) " (${v.second} capped)" else "" })
            if (poolToken === token) {
                ids.forEach { if (it !in builds) builds[it] = null }
                changed(result, builds, after, before)
                val wornBy = HashMap<Long, Long>().also { m -> ids.forEach { h -> worn(h).forEach { m[it] = h } } }
                val made = ids.flatMap { t -> result[t]?.items.orEmpty().mapNotNull { i -> wornBy[i.id]?.takeIf { it != t }?.let { h -> Triple(h, t, i.id) } } }
                    .groupBy({ it.first to it.second }, { it.third }).map { (k, items) -> Trade(k.second, k.first, items, true) }
                    // a made trade is only worth a look when its wearer paid for it; when both ended better there is nothing to decide
                    .filter { t -> (after[t.holder] ?: 0) < (before[t.holder] ?: 0) }
                val notMade = pr.refusals(result).groupBy { it.holder to it.taker }
                    .map { (k, ds) -> Trade(k.second, k.first, ds.map { it.item }, false, ds.first().takerPoints, ds.first().holderPoints, ds.first().moves) }
                // a piece the player kept is never asked for again, but stays listed so the call can be undone
                val kept = poolKeep.entries.filter { (item, h) -> notMade.none { item in it.items } && result[h]?.items?.any { it.id == item } == true }
                    .groupBy({ it.value }, { it.key }).flatMap { (h, items) -> lastTrades.filter { it.holder == h && it.items.any { i -> i in items } }
                        .map { Trade(it.taker, h, it.items.filter { i -> i in items }, false) } }
                val stuck = poolGive.filterKeys { it in pr.unmoved }.entries.groupBy({ it.value }, { it.key }).flatMap { (t, items) ->
                    items.groupBy { i -> wornBy[i] }.mapNotNull { (h, its) -> h?.let { Trade(t, it, its, false, stuck = true) } }
                }
                val trades = made + notMade.filter { n -> stuck.none { s -> s.taker == n.taker && s.holder == n.holder } } + kept + stuck
                viewModelScope.launch { if (poolToken === token) pool = pool?.copy(builds = LinkedHashMap(builds), after = LinkedHashMap(after), trades = trades) }
            }
            finishPool(token)
        }
    }

    /** Copies a run's assignment into [builds]: a new build for heroes whose gear changed, null for the rest. */
    private fun changed(assign: Map<Long, Build>, builds: MutableMap<Long, Build?>, after: MutableMap<Long, Int?>, before: Map<Long, Int?>) {
        for ((id, b) in assign) {
            if (id !in builds) continue
            val moved = b.items.any { it.equippedOn != id || it.mod != null }
            builds[id] = if (moved) b else null
            after[id] = if (moved) matchOf(id, b)?.pct else before[id]
        }
    }

    private fun finishPool(token: Any) = viewModelScope.launch {
        if (poolToken !== token) return@launch
        PoolService.stop(getApplication())
        val done = pool?.copy(running = false, current = null, currentBest = null, finishedAt = System.currentTimeMillis()) ?: return@launch
        val score = poolScore(done.ids.associateWith { done.after[it] ?: done.before[it] })
        if (done.ids.isNotEmpty()) poolSecondsPerHero = (done.finishedAt - done.startedAt) / 1000.0 / done.ids.size
        // into history before the run reads as done: whatever reacts to "done" finds it there
        record(e7.gearscan.data.HistoryEntry(done.startedAt, true, "Whole pool · ${done.ids.size} heroes", "${poolLosses.label} · ${poolEffort.label}",
            poolScore(done.before), score, done.moves,
            done.builds.filterValues { it != null }.mapValues { (_, b) -> listOf(b!!.items.filter { !it.ghost }.map(e7.gearscan.data.PieceRef::of)) },
            done.before, done.after))
        pool = done
        pendingPool = done.startedAt
        lastPool = System.currentTimeMillis() to score
        save()
        notifyPoolDone(done)
    }

    /** Runs one pool hero's search, showing its best match so far. */
    private fun runWatched(id: Long, r: SearchRun, mods: Boolean, token: Any) {
        run = r
        val watch = thread(name = "pool-watch") {
            // interrupted when the hero's search ends; that is the normal way out
            try {
                while (poolToken === token && !r.engine.done) {
                    Thread.sleep(400)
                    val best = r.builds().firstOrNull()?.let { matchOf(id, it)?.pct }
                    viewModelScope.launch { pool = pool?.copy(current = id, currentBest = best) }
                }
            } catch (_: InterruptedException) {}
        }
        viewModelScope.launch { pool = pool?.copy(current = id, currentBest = null) }
        // mods search a few times more pieces; give those heroes twice the time
        r.run(Runtime.getRuntime().availableProcessors().coerceIn(1, 6), if (mods) 2 * POOL_HERO_MS else POOL_HERO_MS)
        watch.interrupt()
    }

    private fun worn(id: Long): Set<Long> = box?.gearOf(id)?.values.orEmpty().map { it.id }.toSet()

    /** Another hero of the finished pool run: its new build next to what it wears, or its setup if the run left it as is. */
    fun openPoolHero(id: Long) {
        if (pool?.builds?.get(id) != null) go(Screen.Compare(0, id)) else go(Screen.PoolHero(id, setup = false))
    }

    // swiped away from recents: nothing is left to show the result, so stop rather than leave the service running
    override fun onCleared() {
        if (pool?.running == true) stopPool()
        super.onCleared()
    }

    fun stopPool() {
        run?.cancel()
        poolToken = null
        PoolService.stop(getApplication())
        pool = pool?.copy(running = false, current = null, currentBest = null, finishedAt = System.currentTimeMillis())
    }

    fun equipPool(run: PoolState? = pool) {
        val p = run ?: return
        val prev = LinkedHashMap<Long, Long?>()
        // first owner wins: undo must restore the box as it was before any of these equips
        for ((id, b) in p.builds) b?.let { equip(id, it).forEach { (item, owner) -> prev.putIfAbsent(item, owner) } }
        // equipping is what makes the run done
        if (pendingPool == p.startedAt) { pendingPool = null; save() }
        showToast("Equipped ${p.builds.count { it.value != null }} heroes in this app", "Undo") { undoEquip(prev); pendingPool = p.startedAt; save() }
    }

    /** The last pool run, waiting until it is equipped or another run replaces it; its history entry's start time. */
    var pendingPool: Long? by mutableStateOf(null)

    /** How long a pool run takes per hero on this phone, from the last full run; a slow phone's figure until then. */
    var poolSecondsPerHero by mutableStateOf(3.0)

    /** Brings the pending run back as a live result (equip allowed), from its history entry. */
    fun openPending() {
        val at = pendingPool ?: return
        if (pool?.startedAt != at || pool?.fromHistory == true) {
            val e = history.firstOrNull { it.at == at } ?: run { pendingPool = null; save(); return }
            val ids = e.beforeMatch.keys.filter { hero(it) != null }.sortedWith(compareByDescending<Long> { tier(it) }.thenBy { hero(it)?.let(::heroName) })
            pool = PoolState(ids, e.beforeMatch, ids.associateWith { id -> e.builds[id]?.firstOrNull()?.let { restoreBuild(id, it) } }, e.afterMatch,
                null, null, false, e.at, e.at, reviewed = e.reviewed)
        }
        go(Screen.PoolRun)
    }

    /** A hero's setup as the pool run reads it (not its reviewed mark): what an edit while reviewing changes. */
    fun setupKey(id: Long): String = request(id).toJson().apply { remove("reviewed") }.toString() + config(id).artifact

    /** Heroes whose setup changed since the shown run used it. */
    fun editedSinceRun(): List<Long> = pool?.let { p -> p.setups.filter { (id, k) -> setupKey(id) != k }.keys.toList() }.orEmpty()

    /** Loads one hero's build as the in-game checklist; finishing it records the build. */
    fun loadBuildChecklist(heroId: Long, b: Build, title: String) =
        e7.gearscan.overlay.OverlayState.load(overlayBuild(heroId, b, title)) { recordBuild(heroId, b) }

    /** Loads the pool's moves as the in-game checklist; finishing it records them all. */
    fun loadPoolChecklist(): Boolean {
        val p = pool ?: return false
        val b = poolOverlay() ?: return false
        e7.gearscan.overlay.OverlayState.load(b) { equipPool(p); tab(Screen.Optimize) }
        return true
    }

    fun poolOverlay(): e7.gearscan.overlay.OverlayBuild? {
        val p = pool ?: return null
        val pieces = p.builds.flatMap { (id, b) ->
            val name = hero(id)?.let(::heroName) ?: return@flatMap emptyList()
            val ob = b?.let { overlayBuild(id, it, name) } ?: return@flatMap emptyList()
            ob.pieces.filter { !it.onHero }.map { it.copyFor(name) }
        }
        return e7.gearscan.overlay.OverlayBuild("Pool · ${pieces.size} moves", null, pieces, emptyList())
    }

    // ---- history ----
    var history: List<e7.gearscan.data.HistoryEntry> by mutableStateOf(emptyList())
        private set

    private fun record(e: e7.gearscan.data.HistoryEntry) {
        history = (listOf(e) + history.filter { it.at != e.at }).take(e7.gearscan.data.History.KEEP)
        val snap = history
        viewModelScope.launch(Dispatchers.IO) { e7.gearscan.data.History.save(historyFile, snap) }
    }

    /** A stored build on today's gear (a mod put back on its piece); null if the hero or a piece is gone. */
    fun restoreBuild(heroId: Long, refs: List<e7.gearscan.data.PieceRef>): Build? {
        val b = box ?: return null
        val items = refs.map { r -> (b.itemsById[r.id] ?: return null).let { r.mod?.let { m -> e7.gearscan.opt.Mods.apply(it, m) } ?: it } }
        return setup(heroId)?.build(items)
    }

    /** Opens a past run read only; false while a run or search is going, which it would replace. */
    fun openHistory(e: e7.gearscan.data.HistoryEntry): Boolean {
        if (e.pool) {
            if (pool?.running == true) return false
            // the run still waiting to be equipped opens live, not read only
            if (e.at == pendingPool) { openPending(); return true }
            val ids = e.beforeMatch.keys.filter { hero(it) != null }.sortedWith(compareByDescending<Long> { tier(it) }.thenBy { hero(it)?.let(::heroName) })
            pool = PoolState(ids, e.beforeMatch, ids.associateWith { id -> e.builds[id]?.firstOrNull()?.let { restoreBuild(id, it) } }, e.afterMatch,
                null, null, false, e.at, e.at, reviewed = e.reviewed, fromHistory = true)
            go(Screen.PoolRun)
        } else {
            if (search?.running == true) return false
            val id = e.builds.keys.firstOrNull() ?: return false
            val cur = currentBuild(id) ?: return false
            search = SearchState(id, false, e.at, e.at, e.builds[id].orEmpty().mapNotNull { restoreBuild(id, it) }, cur, historyAt = e.at)
            go(Screen.Results)
        }
        return true
    }

    /** Heroes of the shown pool run whose gear changes, in walk order. */
    fun reviewQueue(): List<Long> = pool?.let { p -> p.ids.filter { p.builds[it] != null } }.orEmpty()

    fun markReviewed(id: Long) {
        val p = pool ?: return
        pool = p.copy(reviewed = p.reviewed + id)
        history.firstOrNull { it.at == p.startedAt }?.let { record(it.copy(reviewed = p.reviewed + id)) }
    }

    private fun notifyPoolDone(p: PoolState) {
        val app = getApplication<Application>()
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("pool", "Pool optimize", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(app, 5, Intent(app, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(4, android.app.Notification.Builder(app, "pool").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pool optimized").setContentText("${p.builds.count { it.value != null }} heroes · open to review and equip")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    // ---- over the game ----
    fun overlayBuild(heroId: Long, b: Build, title: String): e7.gearscan.overlay.OverlayBuild {
        val h = hero(heroId)
        val reforged = request(heroId).reforged
        val cur = currentBuild(heroId)
        val pieces = Slot.entries.mapNotNull { s -> b.items.firstOrNull { it.slot == s } }.filter { !it.ghost }.map { it ->
            val ref = reforged && it.reforgeable
            val main = if (ref) e7.gearscan.calc.Reforge.main(it.mainType, it.mainValue) else it.mainValue
            val holder = it.equippedOn
            val set = content.set(it.set)
            e7.gearscan.overlay.OverlayPiece(it, set?.icon, "${it.mainType.short} ${it.mainType.format(main)}",
                // the piece as it looks in the game today; a mod is shown as its own step
                (box?.itemsById?.get(it.id) ?: it).let { orig -> orig.subs.indices.map { i -> orig.subs[i].let { s -> (if (s.modified) "改 " else "") + "${s.type.short} ${s.type.format(e7.gearscan.calc.Calc.subValue(orig, i, reforged))}" } } },
                when (holder) { null -> "in bag"; heroId -> "on now"; else -> "on " + (hero(holder)?.let(::heroName) ?: "other") },
                holder == heroId,
                "${set?.name ?: it.set} ${it.slot.label.lowercase()} +${it.enhance} · ${it.mainType.short} ${it.mainType.format(it.mainValue)}",
                it.mod?.let { m -> "${m.from.short} ${m.from.format(m.fromValue)} → ${m.to.short} ${m.to.format(m.toValue)}" },
                box?.itemsById?.get(it.id) ?: it, reforged)
        }
        val stats = e7.gearscan.data.Stat.entries.map { st -> Triple(st, b.final[st], cur?.final?.get(st) ?: 0.0) }
        return e7.gearscan.overlay.OverlayBuild(title, h?.let(::face), pieces, stats)
    }

    /** True when the overlay started; false when the permission screen must come first. */
    fun showOverGame(heroId: Long, b: Build, title: String): Boolean {
        val app = getApplication<Application>()
        loadBuildChecklist(heroId, b, title)
        if (!e7.gearscan.overlay.OverlayState.canDraw(app)) return false
        e7.gearscan.overlay.OverlayService.stop(app)
        e7.gearscan.overlay.OverlayService.start(app)
        overlaySeen = true; save()
        openGame()
        return true
    }

    // ---- reports ----
    private fun saveScanLog() {
        val t = Capture.logText()
        viewModelScope.launch(Dispatchers.IO) { File(files, "scan.log").writeText(t) }
    }

    fun scanLog(): String = Capture.logText().ifEmpty { File(files, "scan.log").takeIf { it.exists() }?.readText().orEmpty() }

    fun deviceInfo(): String {
        val app = getApplication<Application>()
        val v = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        return "App $v · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}" +
            (lastError?.let { " · last error: $it" } ?: "")
    }

    fun searchContext(): JSONObject? {
        val s = search ?: return null
        val h = hero(s.heroId)
        return JSONObject().put("hero", h?.let(::heroName)).put("request", request(s.heroId).toJson())
            .put("builds", s.builds.size).put("timedOut", s.timedOut).put("cancelled", s.cancelled)
            .put("ghost", s.ghost != null).put("team", s.team.size)
    }

    fun sendReport(report: JSONObject, done: (Reports.Outcome) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                Reports.enqueue(reportsDir, report)
                Reports.flush(reportsDir, content.feedbackUrl, report.getString("id"))
            }
            done(r)
        }
    }

    // ---- files ----
    /** Saves [text] to Downloads/E7GearScan; returns where, and the name it got. */
    fun export(name: String, text: String): Pair<Uri, String> {
        val app = getApplication<Application>()
        val dir = Environment.DIRECTORY_DOWNLOADS + "/E7GearScan"
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        app.contentResolver.delete(coll, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$dir/", name))
        fun insert(n: String) = app.contentResolver.insert(coll, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, n)
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        })!!
        // copies left by an earlier install aren't ours to replace, and Android numbers a name only up to 31 times
        var saved = name
        val uri = runCatching { insert(name) }.getOrElse {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            saved = name.substringBeforeLast('.') + "-$stamp." + name.substringAfterLast('.')
            insert(saved)
        }
        app.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return uri to saved
    }

    fun exportGear(): Pair<Uri, String>? = box?.let { export("gear.txt", it.toText()) }

    fun backupText(): String = JSONObject().put("app", "e7gear").put("version", 1).put("state", stateJson())
        .put("box", box?.json ?: JSONObject.NULL).put("history", JSONArray(history.map { it.toJson() })).toString()

    fun restore(text: String): Boolean = runCatching {
        val j = JSONObject(text)
        if (j.optString("app") != "e7gear") return false
        j.optJSONObject("box")?.let { b -> boxFile.writeText(b.toString()) }
        stateFile.writeText(j.getJSONObject("state").toString())
        // backups from before history was included leave the current history as it is
        j.optJSONArray("history")?.let { h -> historyFile.writeText(h.toString()); history = e7.gearscan.data.History.load(historyFile) }
        configs.clear()
        apply(readFiles())
        touch()
        true
    }.getOrDefault(false)

    fun eraseAll() {
        boxFile.delete(); stateFile.delete(); buildsDir.deleteRecursively()
        configs.clear(); box = null; tiers = emptyMap(); team = emptyList(); lastScan = 0; lastDiff = null
        consent = false; search = null; presets.clear()
        touch()
        stack = listOf(Screen.ScanIntro)
    }

    fun readUri(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    }.getOrNull()

    // ---- persistence ----
    private fun stateJson(): JSONObject = JSONObject().apply {
        put("consent", consent); put("proxyOn", proxyOn); put("proxy", proxy); put("lastScan", lastScan)
        put("decodeSec", decodeSec)
        lastDiff?.let { put("lastDiff", it.toJson()) }
        put("tiers", JSONObject().apply { tiers.forEach { (k, v) -> put(k.toString(), v) } }); put("team", JSONArray(team))
        put("configs", JSONObject().apply { configs.forEach { (k, v) -> put(k.toString(), v.toJson()) } })
        put("showRating", showRating.name)
        put("overlaySeen", overlaySeen)
        put("poolTiers", JSONArray(poolTiers.toList())); put("poolExtra", JSONArray(poolExtra.toList()))
        put("poolSkip", JSONArray(poolSkip.toList())); put("poolLosses", poolLosses.name); put("poolEffort", poolEffort.name)
        pendingPool?.let { put("pendingPool", it) }
        put("contentCheckedAt", contentCheckedAt); put("poolSecondsPerHero", poolSecondsPerHero)
        put("contentMirror", e7.gearscan.data.Mirror.active)
        lastPool?.let { put("lastPoolAt", it.first); put("lastPoolScore", it.second) }
    }

    private val crashFile = File(files, CRASH_FILE)
    var lastCrash: String? by mutableStateOf(null)
        private set

    fun dismissCrash() { lastCrash = null; crashFile.delete() }

    private class Loaded(val box: Box?, val state: JSONObject?)

    private fun readFiles() = Loaded(
        if (boxFile.exists()) runCatching { Box.parse(boxFile.readText()) }.getOrNull() else null,
        if (stateFile.exists()) runCatching { JSONObject(stateFile.readText()) }.getOrNull() else null,
    )

    private fun apply(l: Loaded) {
        box = l.box
        val j = l.state ?: return
        consent = j.optBoolean("consent"); proxyOn = j.optBoolean("proxyOn"); proxy = j.optString("proxy")
        lastScan = j.optLong("lastScan"); decodeSec = j.optInt("decodeSec")
        lastDiff = ScanDiff.fromJson(j.optJSONObject("lastDiff"))
        fun longs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getLong) } ?: emptyList()
        // the old ordered priority list becomes tier 3 for every hero it named
        tiers = j.optJSONObject("tiers")?.let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } }
            ?: longs("priority").associateWith { 3 }
        // teams became Whole pool runs; a saved team keeps its first hero
        team = longs("team").take(1)
        j.optJSONObject("configs")?.let { c -> c.keys().forEach { k -> configs[k.toLong()] = HeroConfig.fromJson(c.getJSONObject(k)) } }
        // early RTA presets saved single pieces of 2-piece sets as requirements; keep only complete sets
        configs.values.forEach { cfg -> cfg.request?.let { r ->
            r.setAlts = r.setAlts.map { alt -> alt.mapNotNull { (k, n) -> content.set(k)?.takeIf { n >= it.pieces }?.let { k to (n / it.pieces) * it.pieces } }.toMap() }
                .filter { it.isNotEmpty() }
        } }
        showRating = runCatching { Rating.valueOf(j.optString("showRating")) }.getOrDefault(Rating.DMG)
        overlaySeen = j.optBoolean("overlaySeen")
        j.optJSONArray("poolTiers")?.let { a -> poolTiers = (0 until a.length()).map(a::getInt).toSet() }
        poolExtra = longs("poolExtra").toSet()
        poolSkip = longs("poolSkip").toSet()
        pendingPool = if (j.has("pendingPool")) j.getLong("pendingPool") else null
        contentCheckedAt = j.optLong("contentCheckedAt")
        e7.gearscan.data.Mirror.active = j.optBoolean("contentMirror")
        poolSecondsPerHero = j.optDouble("poolSecondsPerHero", 3.0)
        poolLosses = runCatching { PoolLosses.valueOf(j.optString("poolLosses")) }.getOrDefault(PoolLosses.SAFE)
        poolEffort = runCatching { PoolEffort.valueOf(j.optString("poolEffort")) }.getOrDefault(PoolEffort.BALANCED)
        if (j.has("lastPoolAt")) lastPool = j.getLong("lastPoolAt") to j.getInt("lastPoolScore")
    }

    fun save() {
        val text = stateJson().toString()
        viewModelScope.launch(Dispatchers.IO) {
            // whole-file replace, so a write cut short never leaves half a state file
            synchronized(stateFile) { File(stateFile.path + ".tmp").also { it.writeText(text) }.renameTo(stateFile) }
        }
    }

    private fun saveBox() {
        val text = box?.toText() ?: return
        viewModelScope.launch(Dispatchers.IO) { boxFile.writeText(text) }
    }

    fun showCapture() {
        if (scanPhase != ScanPhase.IDLE && screen != Screen.Capture) go(Screen.Capture)
    }

    /** "just now", "5 h ago", "3 d ago". */
    fun scannedLabelOf(at: Long): String {
        val h = (System.currentTimeMillis() - at) / 3_600_000
        return when { h < 1 -> "just now"; h < 24 -> "$h h ago"; else -> "${h / 24} d ago" }
    }

    fun scannedLabel(): String {
        if (scanPhase == ScanPhase.CAPTURING) return "Scanning · ${captureBytes / 1024} KB"
        if (scanPhase == ScanPhase.IMPORTING) return "Importing…"
        if (lastScan == 0L) return if (box == null) "Not scanned" else "Imported"
        val h = (System.currentTimeMillis() - lastScan) / 3_600_000
        return when {
            h < 1 -> "Scanned just now"
            h < 24 -> "Scanned $h h ago"
            else -> "Scanned ${h / 24} d ago"
        }
    }

    // last in the class: startup work started here reads fields declared above, which must exist before it runs
    init {
        e7.gearscan.data.Http.agent = "E7Gear/$appVersion"
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { readFiles() }
            lastCrash = withContext(Dispatchers.IO) { crashFile.takeIf { it.exists() }?.readText() }
            apply(loaded)
            history = withContext(Dispatchers.IO) { e7.gearscan.data.History.load(historyFile) }
            box?.let { b -> if (team.isEmpty() || team.any { b.hero(it) == null }) team = listOfNotNull(defaultHero(b)) }
            stack = listOf(if (!consent && box == null) Screen.ScanIntro else Screen.Optimize)
            ready = true
            team.firstOrNull()?.let { ensurePresets(it) }
            if (content.heroes.isEmpty()) checkContent()
            else if (System.currentTimeMillis() - contentCheckedAt > 24 * 3600 * 1000L) checkContent(quiet = true)
            checkAppUpdate()
            withContext(Dispatchers.IO) { AppInstall.clean(getApplication(), appVersion) }
            withContext(Dispatchers.IO) { Reports.flush(reportsDir, content.feedbackUrl) }
        }
        viewModelScope.launch {
            while (true) {
                captureConns = Capture.count()
                captureBytes = Capture.totalBytes()
                // the VPN can end without us (revoked by another VPN, service killed)
                if (scanPhase == ScanPhase.CAPTURING && !TProxyService.running && System.currentTimeMillis() - captureStarted > 5000) {
                    scanPhase = ScanPhase.IDLE
                    if (captureBytes == 0L) scanError = ScanError.NOTHING
                }
                delay(1000)
            }
        }
    }
}
