package e7.gearscan.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import e7.gearscan.AppModel
import e7.gearscan.data.Reports
import e7.gearscan.overlay.OverlayService
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@Composable
fun OverlayIntroScreen(model: AppModel) {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf(false) }
    // came back from Android's settings without the permission: on Android 13+ an app installed outside a store has
    // that switch locked until "Allow restricted settings" is turned on in its App info
    var blocked by remember { mutableStateOf(false) }
    fun go() {
        if (model.graph.settings.overGameNative) { model.graph.settings.nativeSeen = true; OverlayService.stop(ctx) } else OverlayService.start(ctx)
        model.graph.settings.overlaySeen = true
        model.save()
        model.back()
        if (model.graph.tracker.shown) model.graph.tracker.runCheck()
        model.graph.scan.openGame()
    }
    val native = model.graph.settings.overGameNative
    LifecycleResumeEffect(waiting) {
        if (waiting && model.graph.platform.pillAllowed) { waiting = false; blocked = false; go() }
        else if (waiting) blocked = true
        onPauseOrDispose { }
    }
    Frame(null, null, actions = {
        PinkButton(if (native || model.graph.platform.pillAllowed) "Open game" else "Allow and open game", Modifier.weight(1f)) {
            if (native || model.graph.platform.pillAllowed) go() else {
                waiting = true
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }) {
        BackToPrevious()
        val upgrade = model.graph.tracker.shown
        Text(if (upgrade) "Track upgrades over the game" else "Show this build over the game", style = TitleStyle.copy(fontSize = 24.sp, lineHeight = 28.sp))
        Text(if (upgrade) "Record each +3 in a small floating panel and check whether the piece could improve a hero build." else "A small panel floats on top of Epic Seven, so you can equip each piece without switching apps.",
            style = zen(13.sp, FontWeight.Medium, C.muted))
        Segmented(listOf("E7 Gear's panel", "Phone's floating window"), if (native) 1 else 0) { model.graph.settings.useOverGameNative(it == 1) }
        if (native) Card(padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IntroStep(1, "Open the game", "Tap the pink button. E7 Gear keeps the ${if (upgrade) "tracker" else "checklist"} ready.")
                IntroStep(2, "Open E7 Gear as a floating window", "Use your phone's side bar or recent apps, as for any app. The window shows only the ${if (upgrade) "tracker" else "checklist"}.")
                IntroStep(3, "Move, resize or minimize it", "Your phone handles the window. – shows the whole app, Panel brings the ${if (upgrade) "tracker" else "checklist"} back.")
            }
        } else Card(padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IntroStep(1, "Allow “Display over other apps”", "Android opens its settings page once. Turn it on, then come back.")
                IntroStep(2, "Open the game's gear screen", "The bubble stays at the edge; tap it to open the checklist.")
                if (upgrade) IntroStep(3, "Record the totals after each +3", "Tap Record, enter the stats shown in the game, then save. Your history stays on this phone.")
                else IntroStep(3, "Tap Done as you equip each piece", "The list lets taps through to the game. When all are done, record the build.")
            }
        }
        DashedBox(Modifier.fillMaxWidth(), color = C.edge, padding = PaddingValues(12.dp)) {
            Text("The panel only shows information. It never taps or reads the game screen.", style = zen(12.sp, FontWeight.Medium))
        }
        if (!native && blocked && android.os.Build.VERSION.SDK_INT >= 33) DashedBox(Modifier.fillMaxWidth(), color = C.blue, padding = PaddingValues(12.dp)) {
            Text("Switch greyed out, or “Restricted setting”?", style = zen(13.sp, color = C.blue))
            VSpace(4.dp)
            Text("Android locks it for apps installed from a file. Open App info, tap the three-dot menu in the corner, choose “Allow restricted settings”, " +
                "then tap the pink button again.", style = zen(12.sp, FontWeight.Medium))
            VSpace(6.dp)
            LinkText("Open App info") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        else if (waiting) Text("Turn on “Allow display over other apps” for E7 Gear, then come back here.", style = zen(12.sp, color = C.blue))
    }
}

@Composable
private fun IntroStep(n: Int, title: String, sub: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberedStep(n)
        Column {
            Text(title, style = zen(14.sp))
            Text(sub, style = zen(12.sp, FontWeight.Medium, C.muted))
        }
    }
}

@Composable
fun FeedbackScreen(model: AppModel, error: String?) {
    val ctx = LocalContext.current
    var topic by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var withContext by remember { mutableStateOf(true) }
    var withDevice by remember { mutableStateOf(true) }
    var withLog by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Pair<Reports.Outcome, String>?>(null) }
    var sending by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { model.graph.feedback.refreshKnownIssues() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        shot = uri?.let { u -> runCatching { jpegBase64(ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }) }.getOrNull() }
    }
    Frame(null, null, actions = {
        when (outcome?.first) {
            null -> PinkButton(if (sending) "Sending…" else "Send report", Modifier.weight(1f), enabled = text.isNotBlank() && !sending) {
                sending = true
                val id = Reports.newId()
                val r = JSONObject().put("id", id).put("created", System.currentTimeMillis())
                    .put("topic", listOf("bug", "idea", "other")[topic]).put("message", text.trim())
                if (email.isNotBlank()) r.put("email", email.trim())
                if (withContext) model.searchContext()?.let { r.put("context", it) }
                if (withDevice) {
                    r.put("device", model.graph.feedback.deviceInfo())
                    e7.gearscan.data.Problems.peek().takeIf { it.isNotEmpty() }?.let { r.put("problems", org.json.JSONArray(it)) }
                }
                if (withLog) r.put("scanLog", model.graph.feedback.scanLog())
                if (error != null) r.put("error", error)
                shot?.let { r.put("screenshot", it) }
                model.graph.feedback.send(r) { o -> sending = false; outcome = o to id }
            }
            else -> PinkButton("Done", Modifier.weight(1f)) { model.back() }
        }
    }) {
        BackToPrevious()
        ScreenTitle("Feedback", "報告")
        if (error != null && outcome == null) StateCard("Opened from an error", "Topic is set to Bug and the error is attached: ${error.lineSequence().first()}")
        when (outcome?.first) {
            Reports.Outcome.SENT -> StateCard("Sent", "Thanks! Report #${outcome!!.second} · we may reply by email if you left one.")
            Reports.Outcome.QUEUED -> StateCard("No connection", "Saved on the phone. It sends by itself next time you are online.")
            Reports.Outcome.NO_ENDPOINT -> StateCard("Saved on the phone",
                "Report #${outcome!!.second} is kept on the phone. Reports can't be sent from this version yet; it goes out by itself once they can.")
            null -> {
                if (model.graph.feedback.knownIssues.isNotEmpty()) KnownIssuesCard(model.graph.feedback.knownIssues)
                Segmented(listOf("Bug", "Idea", "Other"), topic) { topic = it }
                Text("What happened?", style = zen(13.sp))
                PasteField(text, if (topic == 0) "Paste what happened and what went wrong" else "Paste your message", 2000, multiline = true) { text = it }
                CheckRow("Include what I was doing", "Hero, preset, targets and rules of the last search", withContext) { withContext = it }
                CheckRow("Include device info", model.graph.feedback.deviceInfo(), withDevice) { withDevice = it }
                CheckRow("Include the last scan log", "Connection log only. No login data.", withLog) { withLog = it }
                CheckRow("Add a screenshot", if (shot != null) "Screenshot attached · tap to remove" else "Pick one from the gallery", shot != null) {
                    if (shot != null) shot = null else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                PasteField(email, "Email for a reply (optional)", 120) { email = it.trim() }
                Text("Only what is ticked is sent. Your gear and account id are never included.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
    }
}

@Composable
fun KnownIssuesCard(issues: List<e7.gearscan.data.KnownIssue>, startOpen: Boolean = false) {
    var open by remember { mutableStateOf(startOpen) }
    var all by remember { mutableStateOf(false) }
    Card(radius = 14.dp, border = 2.dp, shadowOffset = 3.dp) {
        Row(Modifier.fillMaxWidth().tap { open = !open }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Dela's tall ascent makes centre alignment put the badge visibly high; baselines line the digits up with the title
            Text("Already known", style = dela(15.sp).pinkShadow(2.5f), modifier = Modifier.alignByBaseline())
            Text("${issues.size}", maxLines = 1, softWrap = false, style = zen(11.sp, FontWeight.Black, C.card),
                modifier = Modifier.alignByBaseline().clip(RoundedCornerShape(50)).background(C.ink).padding(horizontal = 7.dp))
            Box(Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = zen(12.sp, FontWeight.Bold, C.muted), modifier = Modifier.alignByBaseline())
            Text("›", style = dela(16.sp, C.muted), modifier = Modifier.rotate(if (open) -90f else 90f))
        }
        if (!open) return@Card
        val shown = if (all) issues else issues.take(5)
        for (issue in shown) {
            VSpace(10.dp)
            val tile = RoundedCornerShape(10.dp)
            Column(Modifier.fillMaxWidth().hardShadow(C.shadow, 2.dp, 2.dp, 10.dp).clip(tile).background(C.field)
                .border(2.dp, C.edge, tile).padding(horizontal = 10.dp, vertical = 8.dp)) {
                when (issue.status) {
                    "working" -> Tag(issue.label, bg = C.pink, size = 10)
                    "fixed" -> Tag(issue.label, bg = C.blue, fg = C.card, size = 10)
                    else -> Tag(issue.label, bg = C.card, size = 10)
                }
                VSpace(5.dp)
                Text(issue.title, style = zen(13.sp, FontWeight.Bold))
                if (issue.detail.isNotEmpty()) Text(issue.detail, style = zen(11.sp, FontWeight.Medium, C.muted))
            }
        }
        if (shown.size < issues.size) {
            VSpace(10.dp)
            Text("Show all ${issues.size}", style = zen(12.sp, FontWeight.Bold, C.pink), modifier = Modifier.tap { all = true }.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun StateCard(title: String, body: String) {
    Card(shadow = null, radius = 14.dp, border = 2.dp) {
        Text(title, style = dela(14.sp))
        VSpace(4.dp)
        Text(body, style = zen(12.sp, FontWeight.Medium, C.muted))
    }
}

@Composable
private fun CheckRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().tap { onChange(!on) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(on, onChange)
        Column(Modifier.weight(1f)) {
            Text(title, style = zen(13.sp))
            Text(sub, style = zen(11.sp, FontWeight.Medium, C.muted))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(C.line))
}

/** JPEG small enough for the report endpoint's 200 KB screenshot limit. */
private fun jpegBase64(bytes: ByteArray): String {
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    for ((side, quality) in listOf(1280 to 80, 1080 to 70, 900 to 60, 720 to 50)) {
        val scale = minOf(1f, side.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (b64.length <= 190_000) return b64
    }
    error("screenshot too large")
}

/** First start: the APK carries no game data, so heroes, artifacts and sets come from the content site once. */
@Composable
fun GameDataScreen(model: e7.gearscan.AppModel) {
    val failed = model.graph.content.status?.startsWith("Couldn't") == true
    Frame(null, null, actions = if (failed) ({ PinkButton("Try again", Modifier.weight(1f)) { model.graph.content.checkNow() } }) else null) {
        ScreenTitle("Game data")
        Text(if (failed) "Couldn't get the game data" else "Getting game data…", style = dela(16.sp))
        Text("Heroes, artifacts and sets come from the app's content site, so new heroes arrive without an app update. " +
            "This happens once; after that the app checks for newer data at most once a day.", style = zen(13.sp, FontWeight.Medium, C.muted))
        if (failed) Text((model.graph.content.status ?: "") + ". Check the connection and try again.", style = zen(12.sp, color = C.deepPink))
    }
}
