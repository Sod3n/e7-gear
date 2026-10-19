package e7.gearscan.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import e7.gearscan.AppModel
import e7.gearscan.data.Reports
import e7.gearscan.overlay.OverlayService
import e7.gearscan.overlay.OverlayState
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
        OverlayService.start(ctx)
        model.overlaySeen = true
        model.save()
        model.back()
        model.openGame()
    }
    LifecycleResumeEffect(waiting) {
        if (waiting && OverlayState.canDraw(ctx)) { waiting = false; blocked = false; go() }
        else if (waiting) blocked = true
        onPauseOrDispose { }
    }
    Frame(null, null, actions = {
        PinkButton(if (OverlayState.canDraw(ctx)) "Open game" else "Allow and open game", Modifier.weight(1f)) {
            if (OverlayState.canDraw(ctx)) go() else {
                waiting = true
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }) {
        BackToPrevious(model)
        Text("Show this build over the game", style = TitleStyle.copy(fontSize = 24.sp, lineHeight = 28.sp))
        Text("A small panel floats on top of Epic Seven, so you can equip each piece without switching apps.",
            style = zen(13.sp, FontWeight.Medium, C.muted))
        Card(padding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IntroStep(1, "Allow “Display over other apps”", "Android opens its settings page once. Turn it on, then come back.")
                IntroStep(2, "Open the game's gear screen", "The bubble stays at the edge; tap it to open the checklist.")
                IntroStep(3, "Tap Done as you equip each piece", "The list lets taps through to the game. When all are done, record the build.")
            }
        }
        DashedBox(Modifier.fillMaxWidth(), color = C.ink, padding = PaddingValues(12.dp)) {
            Text("The panel only shows information. It never taps or reads the game screen.", style = zen(12.sp, FontWeight.Medium))
        }
        if (blocked && android.os.Build.VERSION.SDK_INT >= 33) DashedBox(Modifier.fillMaxWidth(), color = C.blue, padding = PaddingValues(12.dp)) {
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
                    r.put("device", model.deviceInfo())
                    e7.gearscan.data.Problems.peek().takeIf { it.isNotEmpty() }?.let { r.put("problems", org.json.JSONArray(it)) }
                }
                if (withLog) r.put("scanLog", model.scanLog())
                if (error != null) r.put("error", error)
                shot?.let { r.put("screenshot", it) }
                model.sendReport(r) { o -> sending = false; outcome = o to id }
            }
            else -> PinkButton("Done", Modifier.weight(1f)) { model.back() }
        }
    }) {
        BackToPrevious(model)
        ScreenTitle("Feedback", "報告")
        if (error != null && outcome == null) StateCard("Opened from an error", "Topic is set to Bug and the error is attached: ${error.lineSequence().first()}")
        when (outcome?.first) {
            Reports.Outcome.SENT -> StateCard("Sent", "Thanks! Report #${outcome!!.second} · we may reply by email if you left one.")
            Reports.Outcome.QUEUED -> StateCard("No connection", "Saved on the phone. It sends by itself next time you are online.")
            Reports.Outcome.NO_ENDPOINT -> StateCard("Saved on the phone",
                "Report #${outcome!!.second} is kept on the phone. Reports can't be sent from this version yet; it goes out by itself once they can.")
            null -> {
                Segmented(listOf("Bug", "Idea", "Other"), topic) { topic = it }
                Text("What happened?", style = zen(13.sp))
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.fillMaxWidth().height(116.dp).clip(shape).background(C.card).border(2.5.dp, C.ink, shape).padding(10.dp)) {
                    if (text.isEmpty()) Text(if (topic == 0) "What did you do, and what went wrong?" else "Tell us more", style = zen(13.sp, FontWeight.Medium, C.faint))
                    BasicTextField(text, { text = it.take(2000) }, textStyle = zen(13.sp, FontWeight.Medium), modifier = Modifier.fillMaxWidth().height(80.dp))
                    Text("${text.length} / 2000", style = zen(10.sp, FontWeight.Medium, C.muted), modifier = Modifier.align(Alignment.BottomEnd))
                }
                CheckRow("Include what I was doing", "Hero, preset, targets and rules of the last search", withContext) { withContext = it }
                CheckRow("Include device info", model.deviceInfo(), withDevice) { withDevice = it }
                CheckRow("Include the last scan log", "Connection log only. No login data.", withLog) { withLog = it }
                CheckRow("Add a screenshot", if (shot != null) "Screenshot attached · tap to remove" else "Pick one from the gallery", shot != null) {
                    if (shot != null) shot = null else pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.card)
                    .border(2.dp, C.ink, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (email.isEmpty()) Text("Email for a reply (optional)", style = zen(12.sp, FontWeight.Medium, C.faint))
                    BasicTextField(email, { email = it.take(120) }, textStyle = zen(12.sp, FontWeight.Medium), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                }
                Text("Only what is ticked is sent. Your gear and account id are never included.", style = zen(11.sp, FontWeight.Medium, C.muted))
            }
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
    val failed = model.contentStatus?.startsWith("Couldn't") == true
    Frame(null, null, actions = if (failed) ({ PinkButton("Try again", Modifier.weight(1f)) { model.checkContent() } }) else null) {
        ScreenTitle("Game data")
        Text(if (failed) "Couldn't get the game data" else "Getting game data…", style = dela(16.sp))
        Text("Heroes, artifacts and sets come from the app's content site, so new heroes arrive without an app update. " +
            "This happens once; after that the app checks for newer data at most once a day.", style = zen(13.sp, FontWeight.Medium, C.muted))
        if (failed) Text((model.contentStatus ?: "") + ". Check the connection and try again.", style = zen(12.sp, color = C.deepPink))
    }
}
