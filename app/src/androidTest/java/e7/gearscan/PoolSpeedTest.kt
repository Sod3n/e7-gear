package e7.gearscan

import android.app.Application
import android.util.Log
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * A real account's Whole pool run on whatever device runs it, timed: restores the "Back up everything" file in the test
 * assets (backup.json, from E7_FIXTURES) and runs the pool as the app does. The breakdown is in logcat under E7Pool.
 */
class PoolSpeedTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun backupPoolRun() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val backup = runCatching { inst.context.assets.open("backup.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("needs backup.json in E7_FIXTURES", backup != null)
        // a foreground activity, so the run's foreground service may start as it does for a player
        rule.setContent {}
        lateinit var model: AppModel
        rule.runOnUiThread { model = AppModel(inst.targetContext.applicationContext as Application) }
        rule.waitUntil(20_000) { model.ready }
        rule.waitUntil(60_000) { model.content.heroes.isNotEmpty() }
        rule.runOnUiThread { assertTrue("backup didn't restore", model.restore(backup!!)) }
        val heroes = model.poolHeroes().count { model.hasBuild(it.id) == true }
        val t0 = System.nanoTime()
        rule.runOnUiThread { model.startPool() }
        rule.waitUntil(30 * 60_000L) { model.pool?.running == false }
        val p = model.pool!!
        Log.i("E7Speed", "%d heroes in %.1f s, score %d -> %d, %d moves".format(java.util.Locale.US, heroes, (System.nanoTime() - t0) / 1e9,
            model.poolScore(p.before), model.poolScore(p.ids.associateWith { p.after[it] ?: p.before[it] }), p.moves))
    }
}
