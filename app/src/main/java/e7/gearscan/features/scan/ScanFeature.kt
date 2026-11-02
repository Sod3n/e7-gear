package e7.gearscan.features.scan

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.features.settings.SettingsFeature
import e7.gearscan.features.feedback.FeedbackFeature
import e7.gearscan.Fribbels
import e7.gearscan.Screen
import e7.gearscan.TProxyService
import e7.gearscan.data.Box
import e7.gearscan.ui.Confirmed
import e7.gearscan.data.ScanDiff
import e7.gearscan.data.ScanStatus
import e7.gearscan.state.BoxEvents
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import e7.gearscan.state.FeatureContext
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class ScanError { NOTHING, SERVER, VPN, NOT_INSTALLED }

enum class ScanPhase { IDLE, CAPTURING, IMPORTING }

class ScanFeature(ctx: FeatureContext, private val settings: SettingsFeature, private val feedback: FeedbackFeature, private val events: BoxEvents) : Feature(ctx, "scan") {
    val capture = CaptureSession()

    var lastScan by saved("lastScan", 0L, Codecs.long)
    var lastDiff by saved("lastDiff", null, DIFF)
    var decodeSec by saved("decodeSec", 0, Codecs.int)
    val boxFile = savedFile("box.json", backup = true, path = "box.json")

    // not a mutableStateOf: snapshot records keep replaced values, and each old box holds a whole JSON tree of the gear
    @Volatile private var boxRef: Box? = null
    private var boxVersion by mutableIntStateOf(0)
    val box: Box?
        get() {
            boxVersion
            return boxRef
        }

    var game: String? = null
        private set

    var phase by mutableStateOf(ScanPhase.IDLE)
    var error: ScanError? by mutableStateOf(null)
    var captureStarted = 0L
    var conns by mutableIntStateOf(0)
    var bytes by mutableStateOf(0L)
    var returnedEarly by mutableStateOf(false)
    // ticks every second while capturing, so "no login seen" appears even when nothing new arrives
    var elapsed by mutableStateOf(0L)
    var afterRestart = false
    var scanNow by mutableStateOf(false)

    val step get() = ScanStatus.of(conns, bytes, elapsed)
    val lobbyLikely get() = bytes > TProxyService.LOBBY_KB * 1024
    val pillAllowed get() = ctx.platform.pillAllowed

    fun detectGame() {
        game = ctx.platform.findGame()
    }

    fun replaceBox(b: Box?) {
        boxRef = b
        boxVersion++
    }

    fun commit() {
        ctx.rev.bump()
        saveBox()
        ctx.store.saveNow()
    }

    fun saveBox() {
        box?.let { boxFile.write(it.toText()) }
    }

    fun readBox(): Box? = boxFile.takeIf { it.exists }?.let { f -> runCatching { Box.parse(f.read()!!) }.getOrNull() }

    fun adopt(b: Box? = readBox()) {
        val old = box
        replaceBox(b)
        events.boxReplacing()
        events.boxChanged(old, b)
        events.boxAdopted(b)
    }

    fun import(text: String) {
        ctx.work.cancelAll()
        ctx.platform.stopOverlay(onlyWhenTracking = true)
        val nb = Box.parse(text)
        val old = box
        lastDiff = ScanDiff.between(old, nb)
        replaceBox(nb)
        lastScan = System.currentTimeMillis()
        events.boxChanged(old, nb)
        boxFile.write(text)
        ctx.rev.bump()
        ctx.store.saveNow()
        events.imported()
    }

    fun backupText(): String {
        saveBox()
        ctx.disk.flush()
        return ctx.store.backup().toString()
    }

    fun restore(text: String): Boolean =
        runCatching {
            val j = JSONObject(text)
            if (j.optString("app") != "e7gear") return false
            if (!j.has("state") && !j.has("fields")) return false
            stopAll()
            ctx.store.restore(j)
            adopt()
            events.restored()
            true
        }.getOrDefault(false)

    fun erase(confirmed: Confirmed) {
        stopAll()
        ctx.store.erase()
        events.erased()
        adopt()
        ctx.nav.stack = listOf(Screen.ScanIntro)
    }

    private fun stopAll() {
        ctx.work.cancelAll()
        ctx.platform.stopOverlay()
    }

    fun otherVpnActive() = ctx.platform.otherVpnActive() && phase != ScanPhase.CAPTURING

    fun onVpnGranted() {
        val game = game ?: run { error = ScanError.NOT_INSTALLED; return }
        capture.clear()
        capture.clearLog()
        error = null
        returnedEarly = false
        ctx.platform.startCapture(game, if (settings.proxyOn) settings.proxy.trim() else "")
        phase = ScanPhase.CAPTURING
        captureStarted = System.currentTimeMillis()
        if (ctx.nav.screen != Screen.Capture) ctx.nav.go(Screen.Capture)
        ctx.scope.launch {
            delay(800)
            ctx.platform.openGame(game)
        }
    }

    fun openGameSettings() {
        game?.let(ctx.platform::openGameSettings)
    }

    fun openGame() {
        game?.let(ctx.platform::openGame)
    }

    fun restartGameDuringScan() {
        captureStarted = System.currentTimeMillis()
        elapsed = 0L
        ctx.platform.resetCaptureClock()
        openGameSettings()
    }

    fun allowPill() = ctx.platform.allowPill()

    fun showCapture() {
        if (phase != ScanPhase.IDLE && ctx.nav.screen != Screen.Capture) ctx.nav.go(Screen.Capture)
    }

    fun stopAndImport() {
        if (phase == ScanPhase.CAPTURING) ctx.platform.stopCapture()
        phase = ScanPhase.IMPORTING
        error = null
        val t0 = System.currentTimeMillis()
        thread(name = "import") {
            var cause: String? = null

            fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
            val result =
                runCatching {
                    val segments = capture.hexSegments()
                    if (segments.isEmpty()) return@runCatching ScanError.NOTHING to null
                    val resp =
                        try {
                            Fribbels.getItems(ctx.http, segments)
                        } catch (e: IOException) {
                            cause = describe(e)
                            return@runCatching ScanError.SERVER to null
                        }
                    val r = Fribbels.convert(resp)
                    if (r.items.length() == 0) return@runCatching ScanError.NOTHING to null
                    null to JSONObject().put("items", r.items).put("heroes", r.heroes).toString()
                }.getOrElse {
                    cause = describe(it)
                    ScanError.NOTHING to null
                }
            ctx.scope.launch {
                phase = ScanPhase.IDLE
                val (err, text) = result
                if (err != null || text == null) {
                    error = err ?: ScanError.NOTHING
                    // the reason goes into bug reports ("last error"), so a failed import can be told apart later
                    feedback.lastError = "scan: ${error!!.name.lowercase()}" + (cause?.let { " · $it" } ?: "")
                    feedback.saveScanLog(capture.logText())
                    return@launch
                }
                feedback.saveScanLog(capture.logText())
                capture.clear()
                decodeSec = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(1)
                import(text)
                ctx.nav.replace(Screen.ScanResult)
            }
        }
    }

    suspend fun watch() {
        while (true) {
            conns = capture.count()
            bytes = capture.totalBytes()
            elapsed = if (phase == ScanPhase.CAPTURING) System.currentTimeMillis() - captureStarted else 0L
            // the VPN can end without us (revoked by another VPN, service killed)
            if (phase == ScanPhase.CAPTURING && !capture.vpnRunning && System.currentTimeMillis() - captureStarted > 5000) {
                phase = ScanPhase.IDLE
                if (bytes == 0L) error = ScanError.NOTHING
            }
            delay(1000)
        }
    }

    fun export(
        name: String,
        text: String,
    ): Pair<Uri, String> = ctx.platform.export(name, text)

    fun exportGear(): Pair<Uri, String>? = box?.let { export("gear.txt", it.toText()) }

    fun readUri(uri: Uri): String? = ctx.platform.read(uri)

    fun scannedLabelOf(at: Long): String {
        val h = (System.currentTimeMillis() - at) / 3_600_000
        return when {
            h < 1 -> "just now"
            h < 24 -> "$h h ago"
            else -> "${h / 24} d ago"
        }
    }

    fun scannedLabel(): String {
        if (phase == ScanPhase.CAPTURING) return "Scanning · ${bytes / 1024} KB"
        if (phase == ScanPhase.IMPORTING) return "Importing…"
        if (lastScan == 0L) return if (box == null) "Not scanned" else "Imported"
        val h = (System.currentTimeMillis() - lastScan) / 3_600_000
        return when {
            h < 1 -> "Scanned just now"
            h < 24 -> "Scanned $h h ago"
            else -> "Scanned ${h / 24} d ago"
        }
    }

    private companion object {
        val DIFF: Codec<ScanDiff?> = Codecs.nullable(Codecs.obj<ScanDiff>({ it.toJson() }, { ScanDiff.fromJson(it)!! }))
    }
}
