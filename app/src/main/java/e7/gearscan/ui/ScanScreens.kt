package e7.gearscan.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import e7.gearscan.features.scan.ScanError
import e7.gearscan.features.scan.ScanPhase
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import e7.gearscan.Sheet
import e7.gearscan.SheetKind
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import e7.gearscan.AppGraph
import e7.gearscan.Screen
import e7.gearscan.data.Item
import e7.gearscan.data.ScanStatus
import e7.gearscan.data.ScanStep
import e7.gearscan.data.fmtInt

/**
 * Scan buttons: first the offer to restart the game (a scan needs its login), then the VPN start. Coming back from the
 * game's system page, or picking "not running", starts the scan from here, where the VPN consent result can land.
 */
@Composable
fun rememberStartScan(graph: AppGraph): () -> Unit {
    val scan = rememberScanNow(graph)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && graph.scan.afterRestart) { graph.scan.afterRestart = false; scan() } }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    LaunchedEffect(graph.scan.scanNow) { if (graph.scan.scanNow) { graph.scan.scanNow = false; scan() } }
    return { if (graph.scan.game == null || (graph.scan.otherVpnActive() && !graph.settings.proxyOn)) scan() else graph.nav.sheet = Sheet(SheetKind.SCAN_RESTART, 0) }
}

/** Fresh session for a scan: Android lets no app close another, so the player taps Force stop on the game's page. */
@Composable
@OptIn(LinkStyle::class)
fun ScanRestartSheet(graph: AppGraph) {
    SheetBody("Start the game fresh", button = "Restart Epic Seven", onButton = {
        graph.nav.sheet = null; graph.scan.afterRestart = true; graph.scan.openGameSettings()
    }) {
        Text("The scan reads the game's login, so Epic Seven has to start from its title screen. If it's open, tap Force stop on the next screen and come back: the scan starts by itself.",
            style = zen(13.sp, FontWeight.Medium))
        Text("Android doesn't let one app close another, so this one tap is yours.", style = zen(11.sp, FontWeight.Medium, C.muted))
        OutlineButton("It's not running · scan now", Modifier.fillMaxWidth(), height = 44.dp) { graph.nav.sheet = null; graph.scan.scanNow = true }
    }
}

/** Shared VPN start: the system consent dialog the first time, then the capture screen. */
@Composable
private fun rememberScanNow(graph: AppGraph): () -> Unit {
    val ctx = LocalContext.current
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) graph.scan.onVpnGranted()
    }
    return {
        when {
            graph.scan.game == null -> { graph.scan.error = ScanError.NOT_INSTALLED; if (graph.nav.screen != Screen.Capture) graph.nav.go(Screen.Capture) }
            graph.scan.otherVpnActive() && !graph.settings.proxyOn -> { graph.scan.error = ScanError.VPN; if (graph.nav.screen != Screen.Capture) graph.nav.go(Screen.Capture) }
            else -> VpnService.prepare(ctx)?.let { vpn.launch(it) } ?: graph.scan.onVpnGranted()
        }
    }
}

@Composable
@OptIn(LinkStyle::class)
fun ScanIntroScreen(graph: AppGraph) {
    var agree by remember { mutableStateOf(graph.settings.consent) }
    Frame(null, null,
        primary = Action("Continue", enabled = agree) {
            graph.settings.consent = true; graph.store.saveNow(); graph.nav.replace(Screen.ScanData)
        },
        secondary = Secondary("Not now") { graph.settings.consent = agree; graph.store.saveNow(); graph.nav.replace(Screen.Optimize) }) {
        CrashCard(graph)
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).hardShadow(C.blue, 3.dp, 3.dp, 26.dp).clip(CircleShape).background(C.pink).border(3.dp, C.edge, CircleShape),
                contentAlignment = Alignment.Center) { Text("最", style = dela(24.sp, C.onAccent)) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitText("Gear Optimizer", dela(19.sp).pinkShadow(5f), Modifier.weight(1f, fill = false))
                    Text("BETA", style = dela(10.sp, C.card).copy(letterSpacing = 0.8.sp), softWrap = false, modifier = Modifier.rotate(-4f)
                        .clip(RoundedCornerShape(5.dp)).background(C.blue).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                FitText("ギ ア ・ オ プ テ ィ マ イ ザ ー", zen(11.sp, color = C.muted).copy(letterSpacing = 1.sp))
            }
        }
        Text("Read your heroes and gear from the game", style = dela(24.sp).pinkShadow(6f))
        Text("No PC, no root. The app watches Epic Seven log in once and copies your box.", style = zen(13.sp, FontWeight.Medium, C.muted))
        Card(shadow = C.shadow, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(1, "A VPN for Epic Seven only", "Other apps keep their normal connection.")
                Step(2, "You log in to the lobby", "The app records the game's login data.")
                Step(3, "Fribbels' server decodes it", "Heroes and gear come back and stay on this phone.")
            }
        }
        DashedBox(color = C.blue, radius = 12.dp, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Text("What leaves your phone", style = zen(12.sp, color = C.blue))
            VSpace(4.dp)
            Text(buildAnnotatedString {
                append("The game's login data, sent to Fribbels' server. It includes your ")
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("account id") }
                append(" and ")
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("current game session") }
                append(". Your gear stays on this phone.")
            }, style = zen(13.sp, FontWeight.Medium))
            VSpace(6.dp)
            Text("Other requests carry no account data: game data, portraits and icons from the app's content site, community builds by " +
                "hero name from Fribbels' and the RTA helper's sites. A bug report goes out only when you send one, " +
                "with the phone model and the last error.", style = zen(12.sp, FontWeight.Medium, C.muted))
        }
        Text("Not affiliated with Smilegate. Reading the game's traffic may go against its terms of service; use the scan at your own risk.",
            style = zen(11.sp, FontWeight.Medium, C.muted))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.tap { agree = !agree }) {
            Checkbox(agree) { agree = it }
            Text("I understand what is sent and agree to scan", style = zen(13.sp))
        }
        OutlineButton("Restore a backup or import a gear.txt instead", Modifier.fillMaxWidth(), height = 44.dp) { graph.settings.consent = agree; graph.store.saveNow(); graph.nav.replace(Screen.ScanData) }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberedStep(n)
        Column {
            Text(title, style = zen(14.sp))
            Text(sub, style = zen(12.sp, FontWeight.Medium, C.muted))
        }
    }
}

@Composable
fun ScanDataScreen(graph: AppGraph) {
    val ctx = LocalContext.current
    val start = rememberStartScan(graph)
    var note by remember { mutableStateOf<String?>(null) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { graph.scan.readUri(it) }?.let {
            if (graph.scan.restore(it)) { graph.nav.tab(Screen.Optimize); graph.messages.showToast("Backup restored") } else note = "That file is not a backup from this app."
        }
    }
    val importGear = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { graph.scan.readUri(it) }?.let { t ->
            note = runCatching { graph.scan.import(t); graph.nav.go(Screen.ScanResult); null }.getOrElse { "That file is not a Fribbels gear.txt." }
        }
    }
    fun share(uri: Uri, mime: String) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
    val box = graph.scan.box
    graph.rev.value
    Frame(null, null, primary = Action("Scan now") { if (!graph.settings.consent) graph.nav.go(Screen.ScanIntro) else start() }) {
        BackToPrevious()
        ScreenTitle("Scan & data", "読取")
        Card(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val icon = graph.scan.game?.let { g -> runCatching { ctx.packageManager.getApplicationIcon(g) }.getOrNull() }
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).stripes(C.stripeA, C.stripeB, shape = RoundedCornerShape(10.dp))
                    .border(2.dp, C.edge, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (icon != null) AsyncImage(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (graph.scan.game != null) "Epic Seven found" else "Epic Seven not installed", style = dela(15.sp))
                    Text(box?.let { "${if (graph.scan.lastScan > 0) "Last scan ${graph.scan.scannedLabel().removePrefix("Scanned ")}" else "Imported"} · ${fmtInt(it.items.size.toDouble())} items · ${it.heroes.size} heroes" }
                        ?: "No scan yet", style = zen(11.sp, FontWeight.Medium, C.muted))
                }
            }
        }
        Card(shadow = null, radius = 14.dp, border = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use my VPN as a proxy", style = zen(13.sp))
                    Text("Only if you need your own VPN to play", style = zen(11.sp, FontWeight.Medium, C.muted))
                }
                Toggle(graph.settings.proxyOn) { graph.settings.proxyOn = it; graph.store.saveNow() }
            }
            if (graph.settings.proxyOn) {
                VSpace(8.dp)
                PasteField(graph.settings.proxy, "SOCKS5 host:port", 240) { graph.settings.proxy = it.trim(); graph.store.saveNow() }
                VSpace(4.dp)
                Text("Switch your VPN app to SOCKS5 proxy mode first; Android runs one VPN at a time.",
                    style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        SectionTitle("Your data")
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            // saved straight to Downloads; sharing is an extra the toast offers, not a sheet in the way
            DataRow("Export gear.txt", "Fribbels format, for desktop tools · saved to Downloads/E7GearScan", box != null) {
                graph.scan.exportGear()?.let { (uri, name) -> graph.messages.showToast("Saved $name to Downloads/E7GearScan", "Share") { share(uri, "text/plain") } }
            }
            DataRow("Back up everything", "Heroes, builds, tiers, settings · saved to Downloads/E7GearScan", true) {
                val (uri, name) = graph.scan.export("e7gear-backup.json", graph.scan.backupText())
                graph.messages.showToast("Saved $name to Downloads/E7GearScan", "Share") { share(uri, "application/json") }
            }
            DataRow("Over the game", if (graph.settings.overGameNative) "Phone's floating window · tap to use E7 Gear's panel" else "E7 Gear's panel · tap to use the phone's floating window", true) {
                graph.settings.useOverGameNative(!graph.settings.overGameNative)
            }
            DataRow("Restore from a backup", null, true) { restore.launch(arrayOf("application/json", "text/plain", "*/*")) }
            DataRow("Import a gear.txt file", null, true) { importGear.launch(arrayOf("text/plain", "application/json", "*/*")) }
            DataRow("Game content", graph.content.status ?: ("Version ${graph.content.content.version}" +
                if (graph.content.checkedAt > 0) " · checked ${graph.scan.scannedLabelOf(graph.content.checkedAt)}" else ""), true) { graph.content.checkNow() }
            DataRow("Report a bug", null, true) { graph.nav.go(Screen.Feedback()) }
            Row(Modifier.fillMaxWidth().tap {
                graph.messages.confirm("Erase all data?", "Your scanned gear, hero setups, history and marks are removed from this phone. Theme, proxy and overlay look stay.", "Erase", graph.scan::erase)
            }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("Erase all data", style = zen(13.sp, color = C.deepPink))
            }
        }
        SectionTitle("Credits")
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            DataRow("Fribbels' Epic 7 Optimizer", "Its server decodes every scan. Community builds, the gear.txt format, gear score " +
                "and reforge rules come from it too. Thank you, Fribbels.", true) {
                open("https://github.com/fribbels/Fribbels-Epic-7-Optimizer")
            }
            DataRow("Other credits and licenses", "VPN capture, fonts, and E7 Gear's own code", true) {
                open("https://github.com/Sod3n/e7-gear#credits-and-licenses")
            }
        }
        note?.let { Text(it, style = zen(12.sp, color = C.blue)) }
    }
}

@Composable
private fun DataRow(title: String, sub: String?, enabled: Boolean, onClick: () -> Unit) {
    ListRow(if (enabled) onClick else null, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = zen(13.sp, color = if (enabled) C.ink else C.faint))
        sub?.let { Text(it, style = zen(11.sp, FontWeight.Medium, C.muted)) }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).size(height = 1.5.dp, width = 1.dp).background(C.line))
}

@Composable
@OptIn(LinkStyle::class)
fun CaptureScreen(graph: AppGraph) {
    val start = rememberStartScan(graph)
    val ctx = LocalContext.current
    val err = graph.scan.error
    val capturing = graph.scan.phase == ScanPhase.CAPTURING
    val importing = graph.scan.phase == ScanPhase.IMPORTING
    val step = graph.scan.step
    val game = Secondary("Game", Span.Fixed(96.dp)) { graph.scan.openGame() }
    // the main button is the next step: back to the game until the box arrives, then importing it
    Frame(null, null,
        primary = when {
            importing -> Action("Importing…", enabled = false) {}
            capturing && step == ScanStep.LOBBY -> Action("Import") { graph.scan.stopAndImport() }
            capturing && step == ScanStep.LATE -> Action("Restart game") { graph.scan.restartGameDuringScan() }
            capturing -> Action("Back to the game") { graph.scan.openGame() }
            err == ScanError.SERVER -> Action("Send again") { graph.scan.stopAndImport() }
            err == ScanError.NOT_INSTALLED -> Action("Open Play Store") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Epic%20Seven")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> Action(if (err != null) "Try again" else "Start") { start() }
        },
        secondary = when {
            importing -> null
            capturing && (step == ScanStep.LOBBY || step == ScanStep.LATE) -> game
            capturing -> Secondary("Stop", Span.Fixed(96.dp)) { if (graph.scan.bytes > 0) graph.scan.stopAndImport() else graph.scan.returnedEarly = true }
            else -> null
        }) {
        // leaving keeps the scan running; the header chip on the main screens shows it
        if (graph.nav.stack.size > 1) BackToPrevious()
        if (capturing) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.ink).tap { graph.scan.openGame() }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PulseDot(9.dp)
                Column {
                    Text("Scanning is ON", style = zen(12.sp, color = C.card))
                    Text(if (graph.scan.pillAllowed) "Its status shows over the game · tap it to come back"
                        else "Shown in your notifications · tap there to come back", style = zen(10.sp, FontWeight.Medium, C.faint))
                }
            }
        }
        // one line in every state: a title that wraps when the box arrives would push the checklist down
        FitText(when {
            importing -> "Importing…"
            capturing -> step.title
            err != null -> "Scan"
            else -> "Not scanning"
        }, TitleStyle, short = if (capturing && step == e7.gearscan.data.ScanStep.LOBBY) "Gear arrived" else null)
        when {
            importing -> Card {
                Text("Sending the capture to Fribbels' server and reading your box. This takes a few seconds.", style = zen(14.sp, FontWeight.Medium))
                VSpace(10.dp)
                Box(Modifier.fillMaxWidth().size(height = 8.dp, width = 1.dp).clip(RoundedCornerShape(99.dp)).stripes(C.pink, C.lightPink, shape = RoundedCornerShape(99.dp)))
            }
            err != null -> ErrorCard(err, graph)
            capturing -> Card {
                // every step's message is laid out and the others hidden, so the card keeps the tallest one's height
                Box {
                    for (s in ScanStep.entries) Column(if (s == step) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}) {
                        when (s) {
                            ScanStep.LOBBY -> Tag("Ready to import", bg = C.blue, fg = C.card)
                            ScanStep.LATE -> Tag("Restart needed", bg = C.red, fg = C.card)
                            else -> Tag("Not finished yet")
                        }
                        VSpace(8.dp)
                        Text(s.hint, style = zen(14.sp, FontWeight.Medium))
                    }
                }
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Counter("CONNECTIONS", "${graph.scan.conns}", Modifier.weight(1f))
                    Counter("CAPTURED", "${graph.scan.bytes / 1024} KB", Modifier.weight(1f))
                }
                VSpace(10.dp)
                Milestone("Game connected", graph.scan.conns > 0)
                Milestone("Login started", graph.scan.bytes > ScanStatus.LOGIN_BYTES)
                Milestone("Lobby reached · heroes and gear", graph.scan.bytes > ScanStatus.LOBBY_BYTES)
            }
            else -> Card {
                Text("Start the scan before you open the game: it reads the game's login, so Epic Seven has to start from its title screen. Then log in until the lobby.",
                    style = zen(14.sp, FontWeight.Medium))
            }
        }
        if (!importing && !graph.scan.pillAllowed) LinkText("Show the scan status over the game · allow it", size = 12) { graph.scan.allowPill() }
        if (err != ScanError.NOTHING) Text("The capture is always kept, so any problem can be retried from here.", style = zen(12.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
@OptIn(LinkStyle::class)
private fun ErrorCard(err: ScanError, graph: AppGraph) {
    val (title, body) = when (err) {
        ScanError.NOTHING -> "Nothing captured" to "The scan has to see the game's login. Try again: E7 Gear restarts the game for you, then log in until the lobby."
        ScanError.SERVER -> "Fribbels' server unreachable" to "Your capture is kept (${graph.scan.bytes / 1024} KB)."
        ScanError.VPN -> "Another VPN is on" to "Switch it to proxy mode, then turn on “Use my VPN as a proxy”."
        ScanError.NOT_INSTALLED -> "Epic Seven not installed" to "Install the game, then come back."
    }
    Card(shadow = null, radius = 14.dp, border = 2.dp) {
        Text(title, style = dela(14.sp))
        VSpace(4.dp)
        Text(body, style = zen(12.sp, FontWeight.Medium, C.muted))
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (err == ScanError.VPN) OutlineButton("Open proxy setting", height = 38.dp) { graph.nav.replace(Screen.ScanData) }
            LinkText("Report this") { graph.nav.go(Screen.Feedback("$title: $body")) }
        }
    }
}

@Composable
private fun Counter(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(C.paper).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(label, style = zen(9.sp, color = C.muted).copy(letterSpacing = 0.6.sp))
        Text(value, style = dela(14.sp))
    }
}

@Composable
private fun Milestone(text: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).then(if (done) Modifier.background(C.blue) else Modifier.dashedBorder(C.edge, 1.5.dp, 8.dp)))
        Text(text, style = zen(13.sp, FontWeight.Medium))
    }
}

@Composable
fun ScanResultScreen(graph: AppGraph) {
    val box = graph.scan.box ?: return
    val d = graph.scan.lastDiff
    Frame(null, null, primary = Action("Go to optimizer") { graph.nav.tab(Screen.Optimize) }) {
        if (graph.nav.stack.size > 1) BackToPrevious()
        ScreenTitle("Imported", "取込")
        Card(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(fmtInt(box.items.size.toDouble()), style = dela(32.sp).pinkShadow()); Text("items", style = zen(11.sp, FontWeight.Medium, C.muted)) }
                Box(Modifier.padding(horizontal = 14.dp).width(2.dp).fillMaxHeight().background(C.line))
                Column(Modifier.weight(1f)) { Text(fmtInt(box.heroes.size.toDouble()), style = dela(32.sp).pinkShadow()); Text("heroes", style = zen(11.sp, FontWeight.Medium, C.muted)) }
            }
            VSpace(8.dp)
            Text(if (graph.scan.decodeSec > 0) "Decoded in ${graph.scan.decodeSec} s · saved on this phone" else "Saved on this phone",
                style = zen(11.sp, FontWeight.Medium, C.muted))
        }
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            ListRow({ graph.nav.tab(Screen.Gear) }, Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("Gear", style = zen(14.sp))
                Text("Sell review · Improve heroes · Find gear", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        SectionTitle("Since last scan")
        if (d == null || (d.newItems + d.removedItems + d.enhanced + d.moved).isEmpty() && d.newHeroes.isEmpty()) {
            Text(if (d == null) "This is your first scan." else "Nothing changed since the last scan.", style = zen(13.sp, FontWeight.Medium, C.muted))
        } else {
            Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                DiffRow("New items", "+${d.newItems.size}", C.blue) { graph.nav.go(Screen.DiffItems(0)) }
                DiffRow("Removed items", "−${d.removedItems.size}", C.red, null)
                DiffRow("Enhanced", "${d.enhanced.size}", C.ink) { graph.nav.go(Screen.DiffItems(1)) }
                DiffRow("Moved between heroes", "${d.moved.size}", C.ink) { graph.nav.go(Screen.DiffItems(2)) }
                if (d.newHeroes.isNotEmpty()) DiffRow(if (d.newHeroes.size == 1) "New hero" else "New heroes",
                    d.newHeroes.take(2).joinToString(", ") + if (d.newHeroes.size > 2) " +${d.newHeroes.size - 2}" else "", C.blue, null)
            }
            val preview = d.newItems.take(5).mapNotNull { box.itemsById[it] }
            if (preview.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)) {
                preview.forEach { GearTile(it, 40.dp, setIcon = graph.content.content.set(it.set)?.icon) }
                Text("Tap a row to see the items.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
    }
}

@Composable
private fun DiffRow(label: String, value: String, color: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)?) {
    ListRow(onClick, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), trailing = { Text(value, style = zen(14.sp, color = color)) }) {
        Text(label, style = zen(14.sp))
    }
}

@Composable
fun DiffItemsScreen(graph: AppGraph, kind: Int) {
    val box = graph.scan.box ?: return
    val d = graph.scan.lastDiff ?: return
    val ids = when (kind) { 0 -> d.newItems; 1 -> d.enhanced; else -> d.moved }
    Frame(null, null) {
        BackToPrevious()
        ScreenTitle(listOf("New items", "Enhanced", "Moved")[kind])
        if (ids.isEmpty()) Text("None since the last scan.", style = zen(13.sp, FontWeight.Medium, C.muted))
        ids.mapNotNull { box.itemsById[it] }.forEach { ItemRow(graph, it) }
    }
}

@Composable
fun ItemRow(graph: AppGraph, it: Item, owner: String? = null) {
    Card(shadow = null, radius = 12.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GearTile(it, 44.dp, setIcon = graph.content.content.set(it.set)?.icon)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    StatStack(it, Modifier.weight(1f))
                    // a hero missing from the scan's hero list still wears the piece in the game
                    val who = owner ?: it.equippedOn?.let { id -> graph.heroes.hero(id)?.let(graph.heroes::heroName) ?: "hero not in scan" }
                    Text(who ?: "free", style = zen(11.sp, color = if (who != null) C.blue else C.muted))
                }
                GearPositionLabel(graph, it)
            }
        }
    }
}
