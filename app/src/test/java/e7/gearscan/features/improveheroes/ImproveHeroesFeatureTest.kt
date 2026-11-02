package e7.gearscan.features.improveheroes

import e7.gearscan.AppGraph
import e7.gearscan.TestContent
import e7.gearscan.data.Box
import e7.gearscan.data.Goal
import e7.gearscan.data.UpgradeRecord
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImproveHeroesFeatureTest {
    @After fun reset() {
        Dispatchers.resetMain()
    }

    private val text =
        SyntheticBox.generate(1).gearText.let { full ->
            val box = Box.parse(full)
            val hero = box.heroes.first { box.gearOf(it.id).size == 6 }
            val worn = box.gearOf(hero.id).values.map { it.id }.toSet()
            val spare = box.items.filter { it.id !in worn && it.enhance < 15 && UpgradeRecord.supported(it) }.take(1).map { it.id }.toSet()
            val j = JSONObject(full)
            val items = (0 until j.getJSONArray("items").length()).map { j.getJSONArray("items").getJSONObject(it) }
            val keep = items.filter { it.getLong("id") in worn + spare }.onEach { if (it.getLong("id") in spare) it.put("ingameEquippedId", "None") }
            val heroes = (0 until j.getJSONArray("heroes").length()).map { j.getJSONArray("heroes").getJSONObject(it) }.filter { it.getLong("id") == hero.id }
            JSONObject().put("items", JSONArray(keep)).put("heroes", JSONArray(heroes)).toString()
        }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("improve").toFile(), version: String? = null, import: Boolean = true): AppGraph {
        val content = JSONObject(TestContent.text).also { o -> version?.let { o.put("version", it) } }
        File(dir, "content.json").writeText(content.toString())
        return AppGraph(Disk(dir), scope).also {
            if (import) {
                it.scan.import(text)
                it.scan.lastScan = 1
                val box = it.scan.box!!
                box.heroes.forEach { h -> it.heroes.request(h.id).goal = Goal.SPEED }
            }
        }
    }

    private suspend fun until(what: String, done: () -> Boolean) =
        withContext(Dispatchers.Default) {
            val end = System.currentTimeMillis() + 300_000
            while (!done()) {
                check(System.currentTimeMillis() < end) { "timed out: $what" }
                Thread.sleep(20)
            }
        }

    private suspend fun finished(g: AppGraph) = until("a result") { g.improveHeroes.heroUpgrades.let { it?.result != null && !it.running } }

    private suspend fun stopped(g: AppGraph) =
        until("the progress file") {
            g.disk.flush()
            g.improveHeroes.progress.read() != null
        }

    @Test fun theKeyFlipsOnEachInputAndOnlyThen() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val g = graph(this)
        val h = g.improveHeroes
        val k0 = h.key()
        assertEquals(k0, h.key())

        g.scan.lastScan = 2
        assertNotEquals(k0, h.key())
        g.scan.lastScan = 1
        assertEquals(k0, h.key())

        val item = g.scan.box!!.items.first()
        fun record(scan: Long) = g.tracker.restore(JSONObject().put("records", JSONArray(listOf(UpgradeRecord(scan, listOf(UpgradeRecord.fromScan(item), UpgradeRecord.fromScan(item))).toJson()))), save = false)
        record(9)
        assertEquals(k0, h.key())
        record(1)
        assertNotEquals(k0, h.key())
        g.tracker.reset()
        assertEquals(k0, h.key())

        g.marks.markReviewed(item.id, keep = false)
        assertNotEquals(k0, h.key())
        g.marks.unmark(item.id)
        assertEquals(k0, h.key())

        g.pool.tiers = setOf(1)
        assertNotEquals(k0, h.key())
        g.pool.tiers = setOf(5, 4, 3)
        assertEquals(k0, h.key())

        val hero = h.heroes().first().id
        g.heroes.request(hero).goal = Goal.GEAR_SCORE
        g.rev.bump()
        assertNotEquals(k0, h.key())
        g.heroes.request(hero).goal = Goal.SPEED
        g.rev.bump()
        assertEquals(k0, h.key())

        assertNotEquals(k0, graph(this, version = "other").improveHeroes.key())
        assertEquals(k0, graph(this, version = null).improveHeroes.key())
    }

    @Test fun aStoppedRunContinuesAndItsResultSurvivesARestartAndABoxChange() = runTest(UnconfinedTestDispatcher(), timeout = 6.minutes) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val dir = createTempDirectory("improve").toFile()
        fun restart() = AppGraph(Disk(dir), this).also { it.loadOnce(); it.scan.adopt() }
        val g = graph(this, dir)
        g.improveHeroes.start()
        assertTrue(g.improveHeroes.heroUpgrades!!.running)
        g.improveHeroes.stop()
        stopped(g)
        assertFalse(g.improveHeroes.heroUpgrades?.running == true)
        assertTrue(g.improveHeroes.resumable)
        val saved = JSONObject(g.improveHeroes.progress.read()!!)
        assertFalse(saved.getBoolean("finished"))
        assertEquals(g.improveHeroes.key(), saved.getString("light"))
        g.store.saveNow()
        g.disk.flush()

        val g2 = restart()
        val h = g2.improveHeroes
        assertNull(h.heroUpgrades)
        assertTrue(h.resumable)
        h.start()
        yield()
        finished(g2)
        val result = h.heroUpgrades!!.result!!
        assertFalse(h.resumable)
        assertFalse(h.stale)
        assertTrue(JSONObject(h.progress.read()!!).getBoolean("finished"))
        assertEquals(HERO_UPGRADES_FORMAT, JSONObject(h.result.read()!!).getInt("format"))

        g2.scan.adopt(null)
        assertNull(h.heroUpgrades)
        g2.scan.adopt()
        assertEquals(result.picks.size, h.heroUpgrades!!.result!!.picks.size)
        assertFalse(h.stale)
        g2.scan.lastScan = 2
        assertTrue(h.stale)
        g2.scan.lastScan = 1

        g2.store.saveNow()
        g2.disk.flush()
        val again = restart().improveHeroes
        assertEquals(result.picks.size, again.heroUpgrades!!.result!!.picks.size)
        assertFalse(again.stale)
        assertFalse(again.resumable)
    }
}
