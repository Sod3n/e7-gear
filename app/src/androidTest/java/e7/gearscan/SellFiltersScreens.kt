package e7.gearscan

import android.app.Application
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
    @Test fun editableFiltersForecastReviewQueueAndBackup() {
        assumeTrue(Build.FINGERPRINT.contains("generic")||Build.HARDWARE.contains("ranchu"))
        File(ctx.getExternalFilesDir(null),"sell-filters").deleteRecursively()
        val assets=inst.context.assets
        val content=JSONObject(assets.open("content.json").bufferedReader().use { it.readText() })
        if(File(ctx.getExternalFilesDir(null),"audit-images/face").isDirectory)content.getJSONObject("images").put("face",File(ctx.getExternalFilesDir(null),"audit-images/face").toURI().toString()+"{code}_s.png")
        File(ctx.filesDir,"content.json").writeText(content.toString())
        lateinit var model:AppModel
        ui { model=AppModel(ctx.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        ui { assertTrue(model.restore(assets.open("backup.json").bufferedReader().use { it.readText() }));model.go(Screen.SellFilters) }
        val images=File(ctx.getExternalFilesDir(null),"audit-images")
        if(images.isDirectory)e7.gearscan.ui.GameImages.base=images.toURI().toString()
        var width by mutableIntStateOf(411);var font by mutableFloatStateOf(1f);var overlay by mutableStateOf(false)
        rule.setContent {
            val d=LocalDensity.current;val c=Configuration(LocalConfiguration.current).apply { screenWidthDp=width;screenHeightDp=if(overlay)240 else 850 }
            CompositionLocalProvider(LocalDensity provides Density(d.density,font),LocalConfiguration provides c) {
                Box(Modifier.width(width.dp).then(if(overlay)Modifier.height(240.dp) else Modifier.fillMaxHeight())) {
                    if(overlay)UpgradeOverlay(model.upgrades,{_,_->},{},{}) else App(model){}
                }
            }
        }
        fun noPoints() { rule.onAllNodes(hasText(" pts",substring=true),useUnmergedTree=true).assertCountEquals(0) }
        noPoints();shot("01-roll-review-411")
        ui { width=353;font=1.3f };noPoints();shot("02-roll-review-353-big")
        assertFalse(model.sellQuery.scoreFilter);assertEquals(SellSort.QUALITY,model.sellQuery.sort)
        assertEquals(SellQuery.DEFAULT_GUARDS,model.sellQuery.keepStats);assertTrue(model.sellQuery.includeLocked)
        tap("Find matches");rule.waitUntil(90_000) { !model.sellBusy }
        assertNotNull(model.sellResult);assertTrue(model.sellResult!!.matches.size>20);assertTrue(model.sellFresh)
        val first=model.sellResult!!.matches.first()
        assertTrue(model.sellResult!!.matches.all { !it.scoreUsed })
        noPoints();shot("04-raw-results")
        ui { model.sheet=Sheet(SheetKind.SELL_DETAILS,first.item.id) };noPoints();shot("05-roll-history")
        ui { model.sheet=Sheet(SheetKind.SELL_EXTRA,0) };shot("08-more-filters")
        tap("Also filter by combined score");assertTrue(model.sellQuery.scoreFilter)
        tap("Also filter by combined score");assertFalse(model.sellQuery.scoreFilter);tap("Done")
        // what the safeguard slider commits: Speed 20 alone
        ui { model.sheet=null;model.updateSellQuery(model.sellQuery.copy(keepStats=mapOf(GearStat.SPD to 20.0))) }
        assertEquals(20.0,model.sellQuery.keepStats.getValue(GearStat.SPD),0.0)
        ui { model.sellTab=1 };assertFalse(model.sellFresh);noPoints();shot("10-stale")
        val old=model.screen;ui { model.trackSellMatches(openOverlay=false) };assertEquals(old,model.screen)
        tap("Update matches");rule.waitUntil(90_000) { !model.sellBusy };assertTrue(model.sellFresh)
        val result=model.sellResult!!;assertTrue(result.matches.size>20);assertTrue(result.matches.none { it.protected })
        val protected=result.rows.count { it.protected };assertTrue(protected>0)
        tap("$protected kept by safeguards");noPoints();shot("11-stat-safeguards-applied");tap("$protected kept by safeguards")
        val n=result.matches.size
        ui { model.trackSellMatches(openOverlay=false) }
        val t=model.upgrades
        assertEquals(n,t.queue.size);assertTrue(t.isRollReview);assertTrue(t.queue.all { it.scoreGoal==null&&it.goal==null });noPoints();shot("12-review-tracker")
        ui { OverlayState.expanded=true;OverlayState.usableHeightDp=240f;overlay=true }
        noPoints();shot("13-overlay-review",true)
        rule.onNodeWithContentDescription("Next gear").performClick();rule.waitForIdle();assertEquals(1,t.queueIndex);shot("14-overlay-next",true)
        val index=t.queue.indexOfFirst { e->t.records[e.id]!!.item.let { it.rank=="Epic"&&it.enhance<15 } }
        assertTrue(index>=0);ui { t.selectQueue!!.invoke(index) }
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
        assertTrue(soldId in model.clearedIds);assertEquals(n-1,t.pendingQueue.size);assertNotEquals(soldId,t.selected)
        shot("19-sold-next",true)
        val afterSale=model.backupText()
        val interrupted=JSONObject(afterSale);interrupted.getJSONObject("upgrades").put("selected",soldId)
        ui { assertTrue(model.restore(interrupted.toString())) }
        assertNotEquals(soldId,t.selected);assertTrue(t.queueIndex>=0)
        val stale=JSONObject(afterSale);stale.getJSONObject("upgrades").put("queueScan",model.lastScan-1)
        val remaining=t.selected!!
        ui { assertTrue(model.restore(stale.toString()));model.markOverlaySold(remaining) }
        assertFalse(remaining in model.clearedIds);assertNotNull(t.error)
        ui { assertTrue(model.restore(afterSale)) }
        // Repeated events from the old button cannot mark the new piece.
        ui { model.markOverlaySold(soldId) };assertEquals(n-1,t.pendingQueue.size)
        rule.onNodeWithContentDescription("Undo last mark").performClick();rule.waitForIdle()
        assertFalse(soldId in model.clearedIds);assertEquals(n,t.pendingQueue.size);assertEquals(soldId,t.selected)
        assertEquals(history,t.record!!.history.size);shot("20-undo-sold-mark",true)
        ui { overlay=false;assertTrue(model.restore(afterSale));model.go(Screen.Upgrade) }
        assertTrue(soldId in model.clearedIds);assertEquals(n-1,t.pendingQueue.size);assertTrue(t.canUndoSold)
        ui { model.undoOverlaySold() };assertEquals(soldId,t.selected);assertFalse(soldId in model.clearedIds)
        ui { OverlayState.expanded=true;overlay=true }
        shot("21-sold-mark-restored",true)
        var returned=0;ui { t.returnToApp={returned++} };rule.onNodeWithContentDescription("More").performClick();rule.waitForIdle();tap("Open E7 Gear");assertEquals(1,returned)
        val backup=model.backupText();val saved=JSONObject(backup)
        assertEquals(model.sellQuery,SellQuery.of(saved.getJSONObject("state").getJSONObject("sellQuery")))
        val reopened=UpgradeTracker(File(ctx.filesDir,"upgrades.json"));assertTrue(reopened.isRollReview);assertEquals(t.reviewRolls,reopened.reviewRolls);assertEquals(t.queue,reopened.queue)
        val lastQuery=model.sellQuery
        ui { overlay=false;assertTrue(model.restore(backup));model.go(Screen.SellFilters) }
        assertEquals(lastQuery,model.sellQuery);assertFalse(model.sellQuery.scoreFilter);noPoints()
        ui { model.findSellGear() };rule.waitUntil(90_000) { !model.sellBusy }
        val only=model.sellResult!!.matches.first().item.id
        ui { model.trackSellMatches(only,openOverlay=false);OverlayState.expanded=true;overlay=true }
        tap("Sold ›");assertTrue(t.sellQueueComplete);assertEquals(0,t.pendingQueue.size)
        assertTrue(only in model.clearedIds);shot("22-all-marked",true)
        ui { model.markOverlaySold(only) };assertEquals(0,t.pendingQueue.size)
        tap("Undo mark");assertFalse(t.sellQueueComplete);assertEquals(1,t.pendingQueue.size)
        assertFalse(only in model.clearedIds);shot("23-final-mark-undone",true)
        ui { overlay=false }
        File(ctx.getExternalFilesDir(null),"sell-filters/receipt.txt").writeText("$n real-inventory review matches tracked; $protected left out by chosen Speed20 safeguard; default safeguards, point-free primary UI, combined score toggle, freshness, live procs/forecasts, Sold/Undo, pending positions, restored sale marks, last-item completion, queue and backup restore passed; query=${lastQuery.toJson()}\n")
        ui { OverlayState.upgrade=null;OverlayState.usableHeightDp=0f }
    }
}
