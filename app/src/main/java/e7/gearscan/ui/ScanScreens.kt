package e7.gearscan.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import e7.gearscan.AppModel
import e7.gearscan.ScanError
import e7.gearscan.ScanPhase
import e7.gearscan.Screen
import e7.gearscan.data.Item
import e7.gearscan.data.fmtInt

/**
 * Scan buttons: first the offer to restart the game (a scan needs its login), then the VPN start. Coming back from the
 * game's system page, or picking "not running", starts the scan from here, where the VPN consent result can land.
 */
@Composable
fun rememberStartScan(model: AppModel): () -> Unit {
    val scan = rememberScanNow(model)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && model.scanAfterRestart) { model.scanAfterRestart = false; scan() } }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    LaunchedEffect(model.scanNow) { if (model.scanNow) { model.scanNow = false; scan() } }
    return { if (model.game == null || (model.otherVpnActive() && !model.proxyOn)) scan() else model.sheet = Sheet(SheetKind.SCAN_RESTART, 0) }
}

/** Fresh session for a scan: Android lets no app close another, so the player taps Force stop on the game's page. */
@Composable
fun ScanRestartSheet(model: AppModel) {
    SheetBody("Start the game fresh", button = "Restart Epic Seven", onButton = {
        model.sheet = null; model.scanAfterRestart = true; model.openGameSettings()
    }) {
        Text("The scan reads the game's login, so Epic Seven has to start from its title screen. If it's open, tap Force stop on the next screen and come back: the scan starts by itself.",
            style = zen(13.sp, FontWeight.Medium))
        Text("Android doesn't let one app close another, so this one tap is yours.", style = zen(11.sp, FontWeight.Medium, C.muted))
        LinkText("It's not running · scan now") { model.sheet = null; model.scanNow = true }
    }
}

/** Shared VPN start: the system consent dialog the first time, then the capture screen. */
@Composable
private fun rememberScanNow(model: AppModel): () -> Unit {
    val ctx = LocalContext.current
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) model.onVpnGranted()
    }
    return {
        when {
            model.game == null -> { model.scanError = ScanError.NOT_INSTALLED; if (model.screen != Screen.Capture) model.go(Screen.Capture) }
            model.otherVpnActive() && !model.proxyOn -> { model.scanError = ScanError.VPN; if (model.screen != Screen.Capture) model.go(Screen.Capture) }
            else -> VpnService.prepare(ctx)?.let { vpn.launch(it) } ?: model.onVpnGranted()
        }
    }
}

@Composable
fun ScanIntroScreen(model: AppModel) {
    var agree by remember { mutableStateOf(model.consent) }
    Frame(null, null, actions = {
        PinkButton("Continue", Modifier.weight(1f), enabled = agree) {
            model.consent = true; model.save(); model.replace(Screen.ScanData)
        }
        OutlineButton("Not now") { model.consent = agree; model.save(); model.replace(Screen.Optimize) }
    }) {
        CrashCard(model)
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).hardShadow(C.blue, 3.dp, 3.dp, 26.dp).clip(CircleShape).background(C.pink).border(3.dp, C.ink, CircleShape),
                contentAlignment = Alignment.Center) { Text("最", style = dela(24.sp)) }
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
        Card(shadow = C.ink, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
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
        LinkText("Restore a backup or import a gear.txt instead") { model.consent = agree; model.save(); model.replace(Screen.ScanData) }
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
fun ScanDataScreen(model: AppModel) {
    val ctx = LocalContext.current
    val start = rememberStartScan(model)
    var erase by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { model.readUri(it) }?.let {
            if (model.restore(it)) { model.tab(Screen.Optimize); model.showToast("Backup restored") } else note = "That file is not a backup from this app."
        }
    }
    val importGear = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { model.readUri(it) }?.let { t ->
            note = runCatching { model.importBox(t); model.go(Screen.ScanResult); null }.getOrElse { "That file is not a Fribbels gear.txt." }
        }
    }
    fun share(uri: Uri, mime: String) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
    val box = model.box
    model.rev
    Frame(null, null, actions = {
        PinkButton("Scan now", Modifier.weight(1f)) { if (!model.consent) model.go(Screen.ScanIntro) else start() }
    }) {
        BackToPrevious(model)
        ScreenTitle("Scan & data", "読取")
        Card(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val icon = model.game?.let { g -> runCatching { ctx.packageManager.getApplicationIcon(g) }.getOrNull() }
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).stripes(C.stripeA, C.stripeB, shape = RoundedCornerShape(10.dp))
                    .border(2.dp, C.ink, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (icon != null) AsyncImage(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (model.game != null) "Epic Seven found" else "Epic Seven not installed", style = dela(15.sp))
                    Text(box?.let { "${if (model.lastScan > 0) "Last scan ${model.scannedLabel().removePrefix("Scanned ")}" else "Imported"} · ${fmtInt(it.items.size.toDouble())} items · ${it.heroes.size} heroes" }
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
                Toggle(model.proxyOn) { model.proxyOn = it; model.save() }
            }
            if (model.proxyOn) {
                VSpace(8.dp)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(2.dp, C.ink, RoundedCornerShape(10.dp))
                    .background(C.paper).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    if (model.proxy.isEmpty()) Text("host:port, e.g. 127.0.0.1:10808", style = zen(13.sp, FontWeight.Medium, C.faint))
                    BasicTextField(model.proxy, { model.proxy = it; model.save() }, textStyle = zen(13.sp), singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                VSpace(4.dp)
                Text("Switch your VPN app to SOCKS5 proxy mode first; Android runs one VPN at a time.",
                    style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        SectionTitle("Your data")
        Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            // saved straight to Downloads; sharing is an extra the toast offers, not a sheet in the way
            DataRow("Export gear.txt", "Fribbels format, for desktop tools · saved to Downloads/E7GearScan", box != null) {
                model.exportGear()?.let { (uri, name) -> model.showToast("Saved $name to Downloads/E7GearScan", "Share") { share(uri, "text/plain") } }
            }
            DataRow("Back up everything", "Heroes, builds, tiers, settings · saved to Downloads/E7GearScan", true) {
                val (uri, name) = model.export("e7gear-backup.json", model.backupText())
                model.showToast("Saved $name to Downloads/E7GearScan", "Share") { share(uri, "application/json") }
            }
            DataRow("Restore from a backup", null, true) { restore.launch(arrayOf("application/json", "text/plain", "*/*")) }
            DataRow("Import a gear.txt file", null, true) { importGear.launch(arrayOf("text/plain", "application/json", "*/*")) }
            DataRow("Game content", model.contentStatus ?: ("Version ${model.content.version}" +
                if (model.contentCheckedAt > 0) " · checked ${model.scannedLabelOf(model.contentCheckedAt)}" else ""), true) { model.checkContent() }
            DataRow("Report a bug", null, true) { model.go(Screen.Feedback()) }
            Row(Modifier.fillMaxWidth().tap { if (erase) model.eraseAll() else erase = true }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(if (erase) "Tap again to erase everything" else "Erase all data", style = zen(13.sp, color = C.deepPink))
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
fun CaptureScreen(model: AppModel) {
    val start = rememberStartScan(model)
    val ctx = LocalContext.current
    val err = model.scanError
    val capturing = model.scanPhase == ScanPhase.CAPTURING
    val importing = model.scanPhase == ScanPhase.IMPORTING
    Frame(null, null, actions = {
        when {
            importing -> PinkButton("Importing…", Modifier.weight(1f), enabled = false) {}
            // the main button is the next step: back to the game until the box arrives, then importing it
            capturing && model.lobbyLikely -> {
                PinkButton("Stop and import", Modifier.weight(1f)) { model.stopAndImport() }
                OutlineButton("Game", Modifier.width(96.dp)) { model.openGame() }
            }
            capturing -> {
                PinkButton("Back to the game", Modifier.weight(1f)) { model.openGame() }
                OutlineButton("Stop", Modifier.width(96.dp)) { if (model.captureBytes > 0) model.stopAndImport() else model.returnedEarly = true }
            }
            err == ScanError.SERVER -> PinkButton("Send again", Modifier.weight(1f)) { model.stopAndImport() }
            err == ScanError.NOT_INSTALLED -> PinkButton("Open Play Store", Modifier.weight(1f)) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Epic%20Seven")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> PinkButton(if (err != null) "Try again" else "Start", Modifier.weight(1f)) { start() }
        }
    }) {
        // leaving keeps the scan running; the header chip on the main screens shows it
        if (model.stack.size > 1) BackToPrevious(model)
        if (capturing) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.ink).tap { model.openGame() }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PulseDot(9.dp)
                Column {
                    Text("Scanning the game's login", style = zen(12.sp, color = C.card))
                    Text("Shown in your notifications too · tap there to come back", style = zen(10.sp, FontWeight.Medium, C.faint))
                }
            }
        }
        Text(when {
            importing -> "Importing…"
            capturing -> "Capturing…"
            err != null -> "Scan"
            else -> "Ready to scan"
        }, style = TitleStyle)
        when {
            importing -> Card {
                Text("Sending the capture to Fribbels' server and reading your box. This takes a few seconds.", style = zen(14.sp, FontWeight.Medium))
                VSpace(10.dp)
                Box(Modifier.fillMaxWidth().size(height = 8.dp, width = 1.dp).clip(RoundedCornerShape(99.dp)).stripes(C.pink, C.lightPink, shape = RoundedCornerShape(99.dp)))
            }
            err != null -> ErrorCard(err, model)
            capturing -> Card {
                // both messages are laid out and the other one hidden, so the card keeps the taller one's height
                val done = !model.returnedEarly && model.lobbyLikely
                Box {
                    for (shows in listOf(false, true)) Column(if (shows == done) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}) {
                        if (shows) Tag("Looks complete", bg = C.ghostBg) else Tag("Not finished yet")
                        VSpace(8.dp)
                        Text(if (shows) "Your heroes and gear seem to be here. Press Stop and import."
                            else "Your heroes haven't arrived. Go back to the game and wait for the lobby.", style = zen(14.sp, FontWeight.Medium))
                    }
                }
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Counter("CONNECTIONS", "${model.captureConns}", Modifier.weight(1f))
                    Counter("CAPTURED", "${model.captureBytes / 1024} KB", Modifier.weight(1f))
                }
                VSpace(10.dp)
                Milestone("Game connected", model.captureConns > 0)
                Milestone("Login started", model.captureBytes > 8 * 1024)
                Milestone("Lobby reached · heroes and gear", model.lobbyLikely)
            }
            else -> Card {
                Text("Start the scan, log in to the game until the lobby, then come back and press Stop.", style = zen(14.sp, FontWeight.Medium))
            }
        }
        Text("The capture is always kept, so any problem can be retried from here.", style = zen(12.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun ErrorCard(err: ScanError, model: AppModel) {
    val (title, body) = when (err) {
        ScanError.NOTHING -> "Nothing captured" to "Stopped before the lobby, or started after login. Close the game fully and try again."
        ScanError.SERVER -> "Fribbels' server unreachable" to "Your capture is kept (${model.captureBytes / 1024} KB)."
        ScanError.VPN -> "Another VPN is on" to "Switch it to proxy mode, then turn on “Use my VPN as a proxy”."
        ScanError.NOT_INSTALLED -> "Epic Seven not installed" to "Install the game, then come back."
    }
    Card(shadow = null, radius = 14.dp, border = 2.dp) {
        Text(title, style = dela(14.sp))
        VSpace(4.dp)
        Text(body, style = zen(12.sp, FontWeight.Medium, C.muted))
        VSpace(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (err == ScanError.VPN) LinkText("Open proxy setting") { model.replace(Screen.ScanData) }
            LinkText("Report this") { model.go(Screen.Feedback("$title: $body")) }
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
        Box(Modifier.size(16.dp).clip(CircleShape).then(if (done) Modifier.background(C.blue) else Modifier.dashedBorder(C.ink, 1.5.dp, 8.dp)))
        Text(text, style = zen(13.sp, FontWeight.Medium))
    }
}

@Composable
fun ScanResultScreen(model: AppModel) {
    val box = model.box ?: return
    val d = model.lastDiff
    Frame(null, null, actions = {
        PinkButton("Go to optimizer", Modifier.weight(1f)) { model.tab(Screen.Optimize) }
    }) {
        if (model.stack.size > 1) BackToPrevious(model)
        ScreenTitle("Imported", "取込")
        Card(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(fmtInt(box.items.size.toDouble()), style = dela(32.sp).pinkShadow()); Text("items", style = zen(11.sp, FontWeight.Medium, C.muted)) }
                Box(Modifier.padding(horizontal = 14.dp).width(2.dp).fillMaxHeight().background(C.line))
                Column(Modifier.weight(1f)) { Text(fmtInt(box.heroes.size.toDouble()), style = dela(32.sp).pinkShadow()); Text("heroes", style = zen(11.sp, FontWeight.Medium, C.muted)) }
            }
            VSpace(8.dp)
            Text(if (model.decodeSec > 0) "Decoded in ${model.decodeSec} s · saved on this phone" else "Saved on this phone",
                style = zen(11.sp, FontWeight.Medium, C.muted))
        }
        SectionTitle("Since last scan")
        if (d == null || (d.newItems + d.removedItems + d.enhanced + d.moved).isEmpty() && d.newHeroes.isEmpty()) {
            Text(if (d == null) "This is your first scan." else "Nothing changed since the last scan.", style = zen(13.sp, FontWeight.Medium, C.muted))
        } else {
            Card(shadow = null, radius = 14.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                DiffRow("New items", "+${d.newItems.size}", C.blue) { model.go(Screen.DiffItems(0)) }
                DiffRow("Removed items", "−${d.removedItems.size}", C.red, null)
                DiffRow("Enhanced", "${d.enhanced.size}", C.ink) { model.go(Screen.DiffItems(1)) }
                DiffRow("Moved between heroes", "${d.moved.size}", C.ink) { model.go(Screen.DiffItems(2)) }
                if (d.newHeroes.isNotEmpty()) DiffRow(if (d.newHeroes.size == 1) "New hero" else "New heroes",
                    d.newHeroes.take(2).joinToString(", ") + if (d.newHeroes.size > 2) " +${d.newHeroes.size - 2}" else "", C.blue, null)
            }
            val preview = d.newItems.take(5).mapNotNull { box.itemsById[it] }
            if (preview.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)) {
                preview.forEach { GearTile(it, 40.dp, setIcon = model.content.set(it.set)?.icon) }
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
fun DiffItemsScreen(model: AppModel, kind: Int) {
    val box = model.box ?: return
    val d = model.lastDiff ?: return
    val ids = when (kind) { 0 -> d.newItems; 1 -> d.enhanced; else -> d.moved }
    Frame(null, null) {
        BackToPrevious(model)
        ScreenTitle(listOf("New items", "Enhanced", "Moved")[kind])
        if (ids.isEmpty()) Text("None since the last scan.", style = zen(13.sp, FontWeight.Medium, C.muted))
        ids.mapNotNull { box.itemsById[it] }.forEach { ItemRow(model, it) }
    }
}

@Composable
fun ItemRow(model: AppModel, it: Item, owner: String? = null) {
    Card(shadow = null, radius = 12.dp, border = 2.dp, padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GearTile(it, 44.dp, setIcon = model.content.set(it.set)?.icon)
            Column(Modifier.weight(1f)) {
                Row {
                    Text("${it.mainType.short} ${it.mainType.format(it.mainValue)}", style = zen(13.sp), modifier = Modifier.weight(1f))
                    val who = owner ?: it.equippedOn?.let { id -> model.hero(id)?.let(model::heroName) }
                    Text(who ?: "free", style = zen(11.sp, color = if (who != null) C.blue else C.muted))
                }
                VSpace(3.dp)
                SubGrid(it, reforged = false)
            }
        }
    }
}
