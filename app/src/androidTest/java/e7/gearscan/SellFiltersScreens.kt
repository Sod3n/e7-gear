package e7.gearscan

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import e7.gearscan.data.*
import e7.gearscan.overlay.*
import e7.gearscan.ui.App
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real saved inventory, all controls by touch. Emulator only: never restore fixture data onto a phone. */
class SellFiltersScreens {
    @get:Rule val rule=createComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=inst.targetContext
    private fun ui(f:()->Unit) { rule.runOnUiThread(f);rule.waitForIdle() }
    private fun tap(text:String) {
        val node=rule.onAllNodesWithText(text,useUnmergedTree=true).onFirst()
        runCatching { node.performScrollTo() };node.performTouchInput { click() };rule.waitForIdle()
    }
    private fun choose(name:String) {
        repeat(10) {
            if(rule.onAllNodesWithText(name,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()) { tap(name);return }
            rule.onNodeWithContentDescription("Next choices page").performClick();rule.waitForIdle()
        }
        fail("Choice paging did not reach $name")
    }
    private fun number(label:String) { val node=rule.onNodeWithContentDescription(label);node.performScrollTo();rule.waitForIdle();node.performClick();rule.waitForIdle() }
    private fun step(label:String,n:Int) { rule.onNodeWithContentDescription("Step $label").performClick();repeat(n) { rule.onNodeWithContentDescription("Increase value").performClick() };tap("Use value") }
    private fun shot(name:String,overlay:Boolean=false) {
        rule.onAllNodes(hasSetTextAction(),useUnmergedTree=true).assertCountEquals(0)
        if(overlay) {
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),useUnmergedTree=true).assertCountEquals(0)
            rule.onNodeWithText("···",substring=true).assertIsDisplayed()
            val body=rule.onNodeWithTag("gear-tracker-body",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val text=rule.onAllNodes(hasAnyAncestor(hasTestTag("gear-tracker-body")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true)
            repeat(text.fetchSemanticsNodes().size) { i->val b=text[i].getUnclippedBoundsInRoot();assertTrue("Text outside overlay: $b vs $body",b.top.value>=body.top.value-1&&b.bottom.value<=body.bottom.value+1) }
        }
        inst.sendStatus(2,android.os.Bundle().apply { putString("stream","E7SellAudit screenshot $name\n") })
        val dir=File(ctx.getExternalFilesDir(null),"sell-filters").also { it.mkdirs() }
        val roots=rule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        val bitmap=if(roots==1)rule.onRoot().captureToImage().asAndroidBitmap() else inst.uiAutomation.takeScreenshot()
        bitmap.let { b->File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
    }
    private fun withTracker(backup: String, edit: (JSONObject) -> Unit): String = JSONObject(backup).apply {
        val files = getJSONObject("files")
        files.put("upgrades.json", JSONObject(files.getString("upgrades.json")).also(edit).toString())
    }.toString()

    @Test fun editableFiltersForecastReviewQueueAndBackup() {
        assumeTrue(Build.FINGERPRINT.contains("generic")||Build.HARDWARE.contains("ranchu"))
        File(ctx.getExternalFilesDir(null),"sell-filters").deleteRecursively()
        val assets=inst.context.assets
        val content=JSONObject(assets.open("content.json").bufferedReader().use { it.readText() })
        if(File(ctx.getExternalFilesDir(null),"audit-images/face").isDirectory)content.getJSONObject("images").put("face",File(ctx.getExternalFilesDir(null),"audit-images/face").toURI().toString()+"{code}_s.png")
        File(ctx.filesDir,"content.json").writeText(content.toString())
        lateinit var graph:AppGraph
        ui { graph=startedGraph(ctx) }
        rule.waitUntil(20_000) { graph.ready }
        ui { assertTrue(graph.scan.restore(assets.open("backup.json").bufferedReader().use { it.readText() }));graph.nav.go(Screen.SellFilters) }
        val images=File(ctx.getExternalFilesDir(null),"audit-images")
        if(images.isDirectory)graph.content.imageBase = images.toURI().toString()
        var width by mutableIntStateOf(411);var font by mutableFloatStateOf(1f);var overlay by mutableStateOf(false)
        rule.setContent {
            val d=LocalDensity.current;val c=Configuration(LocalConfiguration.current).apply { screenWidthDp=width;screenHeightDp=if(overlay)240 else 850 }
            CompositionLocalProvider(LocalDensity provides Density(d.density,font),LocalConfiguration provides c) {
                Box(Modifier.width(width.dp).then(if(overlay)Modifier.height(240.dp) else Modifier.fillMaxHeight())) {
                    if(overlay)UpgradeOverlay(graph,{_,_->},{},{}) else App(graph){}
                }
            }
        }
        fun noPoints() { rule.onAllNodes(hasText(" pts",substring=true),useUnmergedTree=true).assertCountEquals(0) }
        noPoints();shot("01-roll-review-411")
        ui { width=353;font=1.3f };noPoints();shot("02-roll-review-353-big")
        assertFalse(graph.sellReview.query.scoreFilter);assertEquals(SellSort.QUALITY,graph.sellReview.query.sort)
        assertEquals(SellQuery.DEFAULT_GUARDS,graph.sellReview.query.keepStats);assertTrue(graph.sellReview.query.includeLocked)
        tap("Find matches");rule.waitUntil(90_000) { !graph.sellReview.busy }
        assertNotNull(graph.sellReview.result);assertTrue(graph.sellReview.result!!.matches.size>20);assertTrue(graph.sellReview.fresh)
        val first=graph.sellReview.result!!.matches.first()
        assertTrue(graph.sellReview.result!!.matches.all { !it.scoreUsed })
        noPoints();shot("04-raw-results")
        ui { graph.nav.sheet=Sheet(SheetKind.SELL_DETAILS,first.item.id) };noPoints();shot("05-roll-history")
        ui { graph.nav.sheet=Sheet(SheetKind.SELL_EXTRA,0) };shot("08-more-filters")
        tap("Also filter by combined score");assertTrue(graph.sellReview.query.scoreFilter)
        tap("Also filter by combined score");assertFalse(graph.sellReview.query.scoreFilter);tap("Done")
        // what the safeguard slider commits: Speed 20 alone
        ui { graph.nav.sheet=null;graph.sellReview.edit(graph.sellReview.query.copy(keepStats=mapOf(GearStat.SPD to 20.0))) }
        assertEquals(20.0,graph.sellReview.query.keepStats.getValue(GearStat.SPD),0.0)
        tap("Matches");assertFalse(graph.sellReview.fresh);noPoints();shot("10-stale")
        val old=graph.nav.screen;ui { graph.sellReview.trackMatches(openOverlay=false) };assertEquals(old,graph.nav.screen)
        tap("Update matches");rule.waitUntil(90_000) { !graph.sellReview.busy };assertTrue(graph.sellReview.fresh)
        val result=graph.sellReview.result!!;assertTrue(result.matches.size>20);assertTrue(result.matches.none { it.protected })
        val protected=result.rows.count { it.protected };assertTrue(protected>0)
        tap("$protected kept by safeguards");noPoints();shot("11-stat-safeguards-applied");tap("$protected kept by safeguards")
        val n=result.matches.size
        ui { graph.sellReview.trackMatches(openOverlay=false) }
        val t=graph.tracker
        assertEquals(n,t.queue.size);assertTrue(t.isRollReview);assertTrue(t.queue.all { it.scoreGoal==null&&it.goal==null });noPoints();shot("12-review-tracker")
        ui { graph.overlay.expanded=true;graph.overlay.usableHeightDp=240f;overlay=true }
        noPoints();shot("13-overlay-review",true)
        rule.onNodeWithContentDescription("Next gear").performClick();rule.waitForIdle();assertEquals(1,t.queueIndex);shot("14-overlay-next",true)
        val index=t.queue.indexOfFirst { e->t.records[e.id]!!.item.let { it.rank=="Epic"&&it.enhance<15 } }
        assertTrue(index>=0);ui { t.selectInQueue(index) }
        rule.onNodeWithContentDescription("More").performClick();rule.waitForIdle();tap("Roll review");noPoints();shot("16-overlay-roll-evidence",true);tap("Back")
        val before=t.record!!.item;val countBefore=t.reviewRolls!!.size;val statText=t.reviewStatText
        val next=(before.enhance/3+1)*3
        tap("Record +$next");val sub=before.subs.first();choose("${sub.type.short}: ${fmtNum(sub.value)}")
        shot("17-overlay-record",true);tap("Apply total");tap("Save +$next")
        assertEquals(next,t.record!!.item.enhance);assertEquals(n,t.queue.size);assertEquals(countBefore+1,t.reviewRolls!!.size);assertNotEquals(statText,t.reviewStatText)
        noPoints();shot("18-overlay-updated",true)
        // This acknowledges only a fixture sale; the audit never touches the game.
        rule.onNodeWithText("Sold ›").assertIsDisplayed()
        val soldId=t.selected!!;val history=t.record!!.history.size
        tap("Sold ›")
        assertTrue(soldId in graph.marks.cleared);assertEquals(n-1,t.pendingQueue.size);assertNotEquals(soldId,t.selected)
        shot("19-sold-next",true)
        val afterSale=graph.scan.backupText()
        val interrupted=withTracker(afterSale) { it.put("selected",soldId) }
        ui { assertTrue(graph.scan.restore(interrupted)) }
        assertNotEquals(soldId,t.selected);assertTrue(t.queueIndex>=0)
        val stale=withTracker(afterSale) { it.put("queueScan",graph.scan.lastScan-1) }
        val remaining=t.selected!!
        ui { assertTrue(graph.scan.restore(stale));graph.tracker.markSold(remaining) }
        assertFalse(remaining in graph.marks.cleared);assertNotNull(t.error)
        ui { assertTrue(graph.scan.restore(afterSale)) }
        // Repeated events from the old button cannot mark the new piece.
        ui { graph.tracker.markSold(soldId) };assertEquals(n-1,t.pendingQueue.size)
        rule.onNodeWithContentDescription("Undo last mark").performClick();rule.waitForIdle()
        assertFalse(soldId in graph.marks.cleared);assertEquals(n,t.pendingQueue.size);assertEquals(soldId,t.selected)
        assertEquals(history,t.record!!.history.size);shot("20-undo-sold-mark",true)
        ui { overlay=false;assertTrue(graph.scan.restore(afterSale));graph.nav.go(Screen.Upgrade) }
        assertTrue(soldId in graph.marks.cleared);assertEquals(n-1,t.pendingQueue.size);assertTrue(t.canUndoSold)
        ui { graph.tracker.undoSold() };assertEquals(soldId,t.selected);assertFalse(soldId in graph.marks.cleared)
        ui { graph.overlay.expanded=true;overlay=true }
        shot("21-sold-mark-restored",true)
        ui { graph.overlay.floating=true;graph.overlay.floatingPanel=true };rule.onNodeWithContentDescription("More").performClick();rule.waitForIdle();tap("Open E7 Gear");assertFalse(graph.overlay.floatingPanel);ui { graph.overlay.floating=false;graph.overlay.floatingPanel=true }
        val backup=graph.scan.backupText();val saved=JSONObject(backup)
        assertEquals(graph.sellReview.query,SellQuery.of(saved.getJSONObject("fields").getJSONObject("sellReview.query")))
        val reopened=reopenTracker(graph,ctx.filesDir);assertTrue(reopened.isRollReview);assertEquals(t.reviewRolls,reopened.reviewRolls);assertEquals(t.queue,reopened.queue)
        val lastQuery=graph.sellReview.query
        ui { overlay=false;assertTrue(graph.scan.restore(backup));graph.nav.go(Screen.SellFilters) }
        assertEquals(lastQuery,graph.sellReview.query);assertFalse(graph.sellReview.query.scoreFilter);noPoints()
        ui { graph.sellReview.find() };rule.waitUntil(90_000) { !graph.sellReview.busy }
        val only=graph.sellReview.result!!.matches.first().item.id
        ui { graph.sellReview.trackMatches(only,openOverlay=false);graph.overlay.expanded=true;overlay=true }
        tap("Sold ›");assertTrue(t.sellQueueComplete);assertEquals(0,t.pendingQueue.size)
        assertTrue(only in graph.marks.cleared);shot("22-all-marked",true)
        ui { graph.tracker.markSold(only) };assertEquals(0,t.pendingQueue.size)
        tap("Undo mark");assertFalse(t.sellQueueComplete);assertEquals(1,t.pendingQueue.size)
        assertFalse(only in graph.marks.cleared);shot("23-final-mark-undone",true)
        ui { overlay=false }
        File(ctx.getExternalFilesDir(null),"sell-filters/receipt.txt").writeText("$n real-inventory review matches tracked; $protected left out by chosen Speed20 safeguard; default safeguards, point-free primary UI, combined score toggle, freshness, live procs/forecasts, Sold/Undo, pending positions, restored sale marks, last-item completion, queue and backup restore passed; query=${lastQuery.toJson()}\n")
        ui { graph.tracker.hide();graph.overlay.usableHeightDp=0f }
    }
}
