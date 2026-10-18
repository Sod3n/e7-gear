package e7.gearscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import e7.gearscan.AppModel
import e7.gearscan.data.HistoryEntry
import e7.gearscan.data.fmtInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val STAMP = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.US)

fun historyStamp(at: Long): String = STAMP.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

/** Optimize's History: every finished pool run and one-hero search, newest first; open one to see its results as they were. */
@Composable
fun HistoryList(model: AppModel) {
    model.rev
    if (model.history.isEmpty()) {
        Text("Every pool run and one-hero search you finish shows up here, to look back at its results.", style = zen(13.sp, FontWeight.Medium, C.muted))
        return
    }
    Card(padding = PaddingValues(0.dp)) {
        model.history.forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.5.dp).background(C.line))
            HistoryRow(model, e)
        }
    }
    Text("Opened runs are read only: gear may have moved since, and each piece shows where it is now. The last ${e7.gearscan.data.History.KEEP} are kept.",
        style = zen(11.sp, FontWeight.Medium, C.muted))
}

@Composable
private fun HistoryRow(model: AppModel, e: HistoryEntry) {
    val hero = if (e.pool) null else e.builds.keys.firstOrNull()?.let { model.hero(it) }
    ListRow({ if (!model.openHistory(e)) model.showToast(if (e.pool) "A pool run is going; open this when it ends" else "A search is going; open this when it ends") },
        Modifier.padding(horizontal = 12.dp, vertical = 10.dp), leading = {
            if (hero != null) Face(model.face(hero), 34.dp)
            else Box(Modifier.size(34.dp).clip(CircleShape).background(C.pink).border(2.dp, C.ink, CircleShape), contentAlignment = Alignment.Center) {
                Text("一括", style = zen(10.sp, FontWeight.Bold))
            }
        }, trailing = {
            Slot(92.dp) {
                FitText(if (e.pool) "${fmtInt(e.before.toDouble())}→${fmtInt(e.after.toDouble())}" else "${e.before}→${e.after}%", zen(12.sp, FontWeight.Bold))
            }
        }) {
        Text(e.title, style = zen(13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${historyStamp(e.at)} · ${e.detail} · ${e.moves} " + (if (e.moves == 1) "move" else "moves") +
            (if (e.pool && e.reviewed.isNotEmpty()) " · ${e.reviewed.size} reviewed" else "") + if (e.at == model.pendingPool) " · pending" else "",
            style = zen(11.sp, FontWeight.Medium, C.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Over a run opened from history: when it was, and why there's nothing to equip. */
@Composable
fun HistoryNote(at: Long) {
    Text("From history · ${historyStamp(at)}. Read only: gear may have moved since; each piece shows where it is now.",
        style = zen(11.sp, FontWeight.Medium, C.deepPink))
}
