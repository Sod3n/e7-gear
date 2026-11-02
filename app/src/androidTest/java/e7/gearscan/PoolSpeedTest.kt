package e7.gearscan

import android.app.Application
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A real account's Whole pool run on whatever device runs it, timed: restores the "Back up everything" file in the test
 * assets (backup.json, from E7_FIXTURES) and runs the pool as the app does. The breakdown is in logcat under E7Pool.
 */
class PoolSpeedTest {
    @Test
    fun backupPoolRun() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val backup = runCatching { inst.context.assets.open("backup.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("needs backup.json in E7_FIXTURES", backup != null)
        // a foreground activity, so the run's foreground service may start as it does for a player; the app's own one,
        // since compose's test activity exists only in debug builds and timings need a release build
        ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: AppModel
        fun waitFor(ms: Long, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (!ok()) { assertTrue("timed out waiting for the model", System.currentTimeMillis() < end); Thread.sleep(100) }
        }
        inst.runOnMainSync { model = AppModel(inst.targetContext.applicationContext as Application) }
        waitFor(20_000) { model.ready }
        waitFor(60_000) { model.graph.content.content.heroes.isNotEmpty() }
        var restored = false
        inst.runOnMainSync { restored = model.graph.scan.restore(backup!!) }
        assertTrue("backup didn't restore", restored)
        val heroes = model.graph.pool.heroes().count { model.graph.heroes.hasBuild(it.id) == true }
        val t0 = System.nanoTime()
        inst.runOnMainSync { model.graph.pool.start() }
        waitFor(30 * 60_000L) { model.graph.pool.state?.running == false }
        val p = model.graph.pool.state!!
        Log.i("E7Speed", "%d heroes in %.1f s, score %d -> %d, %d moves".format(java.util.Locale.US, heroes, (System.nanoTime() - t0) / 1e9,
            model.graph.pool.score(p.before), model.graph.pool.score(p.ids.associateWith { p.after[it] ?: p.before[it] }), p.moves))
    }
}
