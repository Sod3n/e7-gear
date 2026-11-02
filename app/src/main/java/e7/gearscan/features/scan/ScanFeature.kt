package e7.gearscan.features.scan

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import e7.gearscan.AppGraph
import e7.gearscan.Capture
import e7.gearscan.Fribbels
import e7.gearscan.Screen
import e7.gearscan.TProxyService
import e7.gearscan.data.Box
import e7.gearscan.data.ScanDiff
import e7.gearscan.data.ScanStatus
import e7.gearscan.state.Codec
import e7.gearscan.state.Codecs
import e7.gearscan.state.Feature
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class ScanError { NOTHING, SERVER, VPN, NOT_INSTALLED }

enum class ScanPhase { IDLE, CAPTURING, IMPORTING }

class ScanFeature(g: AppGraph) : Feature(g, "scan") {
    interface Host {
        fun imported(box: Box) {}

        fun adopted(box: Box?) {}

        fun restored() {}

        fun erased() {}

        fun beforeBackup() {}
    }

    private object NoHost : Host

    // what the hero, pool, history and tracker sections still keep in AppModel
    var host: Host = NoHost

    var lastScan by saved("lastScan", 0L, Codecs.long)
    var lastDiff by saved("lastDiff", null, DIFF)
    var decodeSec by saved("decodeSec", 0, Codecs.int)
    val boxFile = savedFile("box.json", backup = true, path = "box.json")
    val logFile = savedFile("scan.log", backup = false, path = "scan.log")

    // not a mutableStateOf: snapshot records keep replaced values, and each old box holds a whole JSON tree of the gear
    @Volatile private var boxRef: Box? = null
    private var boxVersion by mutableIntStateOf(0)
    val box: Box?
        get() {
            boxVersion
            return boxRef
        }

    var rev by mutableIntStateOf(0)
        private set

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
    val pillAllowed get() = g.platform.pillAllowed

    fun detectGame() {
        game = g.platform.findGame()
    }

    fun touch() {
        rev++
        g.store.saveSoon()
    }

    fun replaceBox(b: Box?) {
        boxRef = b
        boxVersion++
    }

    fun saveBox() {
        box?.let { boxFile.write(it.toText()) }
    }

    fun readBox(): Box? = boxFile.takeIf { it.exists }?.let { f -> runCatching { Box.parse(f.read()!!) }.getOrNull() }

    fun adopt(b: Box? = readBox()) {
        val old = box
        replaceBox(b)
        g.heroes.adoptConfigs()
        g.sellReview.reset()
        g.findGear.reset()
        g.boxChanged(old, b)
        host.adopted(b)
    }

    fun import(text: String) {
        g.work.cancelAll()
        g.platform.stopOverlay(onlyWhenTracking = true)
        val nb = Box.parse(text)
        val old = box
        lastDiff = ScanDiff.between(old, nb)
        replaceBox(nb)
        lastScan = System.currentTimeMillis()
        g.boxChanged(old, nb)
        boxFile.write(text)
        touch()
        g.store.saveNow()
        host.imported(nb)
    }

    fun otherVpnActive() = g.platform.otherVpnActive() && phase != ScanPhase.CAPTURING

    fun onVpnGranted() {
        val game = game ?: run { error = ScanError.NOT_INSTALLED; return }
        Capture.clear()
        Capture.clearLog()
        error = null
        returnedEarly = false
        g.platform.startCapture(game, if (g.settings.proxyOn) g.settings.proxy.trim() else "")
        phase = ScanPhase.CAPTURING
        captureStarted = System.currentTimeMillis()
        if (g.nav.screen != Screen.Capture) g.nav.go(Screen.Capture)
        g.scope.launch {
            delay(800)
            g.platform.openGame(game)
        }
    }

    fun openGameSettings() {
        game?.let(g.platform::openGameSettings)
    }

    fun openGame() {
        game?.let(g.platform::openGame)
    }

    fun restartGameDuringScan() {
        captureStarted = System.currentTimeMillis()
        elapsed = 0L
        g.platform.resetCaptureClock()
        openGameSettings()
    }

    fun allowPill() = g.platform.allowPill()

    fun showCapture() {
        if (phase != ScanPhase.IDLE && g.nav.screen != Screen.Capture) g.nav.go(Screen.Capture)
    }

    fun stopAndImport() {
        if (phase == ScanPhase.CAPTURING) g.platform.stopCapture()
        phase = ScanPhase.IMPORTING
        error = null
        val t0 = System.currentTimeMillis()
        thread(name = "import") {
            var cause: String? = null

            fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160)
            val result =
                runCatching {
                    val segments = Capture.hexSegments()
                    if (segments.isEmpty()) return@runCatching ScanError.NOTHING to null
                    val resp =
                        try {
                            Fribbels.getItems(segments)
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
            g.scope.launch {
                phase = ScanPhase.IDLE
                val (err, text) = result
                if (err != null || text == null) {
                    error = err ?: ScanError.NOTHING
                    // the reason goes into bug reports ("last error"), so a failed import can be told apart later
                    g.feedback.lastError = "scan: ${error!!.name.lowercase()}" + (cause?.let { " · $it" } ?: "")
                    logFile.write(Capture.logText())
                    return@launch
                }
                logFile.write(Capture.logText())
                Capture.clear()
                decodeSec = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(1)
                import(text)
                g.nav.replace(Screen.ScanResult)
            }
        }
    }

    suspend fun watch() {
        while (true) {
            conns = Capture.count()
            bytes = Capture.totalBytes()
            elapsed = if (phase == ScanPhase.CAPTURING) System.currentTimeMillis() - captureStarted else 0L
            // the VPN can end without us (revoked by another VPN, service killed)
            if (phase == ScanPhase.CAPTURING && !Capture.vpnRunning && System.currentTimeMillis() - captureStarted > 5000) {
                phase = ScanPhase.IDLE
                if (bytes == 0L) error = ScanError.NOTHING
            }
            delay(1000)
        }
    }

    fun export(
        name: String,
        text: String,
    ): Pair<Uri, String> = g.platform.export(name, text)

    fun exportGear(): Pair<Uri, String>? = box?.let { export("gear.txt", it.toText()) }

    fun readUri(uri: Uri): String? = g.platform.read(uri)

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
