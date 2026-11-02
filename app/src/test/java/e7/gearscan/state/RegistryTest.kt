package e7.gearscan.state

import e7.gearscan.AppGraph
import e7.gearscan.calc.Rating
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.PotentialQuery
import e7.gearscan.data.ScanDiff
import e7.gearscan.data.SellQuery
import e7.gearscan.features.marks.MarksFeature
import e7.gearscan.opt.PoolEffort
import e7.gearscan.opt.PoolLosses
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import e7.gearscan.data.Mirror
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegistryTest {
    private class Env(scope: CoroutineScope, val dir: File = createTempDirectory("registry").toFile()) {
        val g = AppGraph(Disk(dir), scope)
        val disk get() = g.disk
        fun text(name: String) = disk.read(name)
    }

    @Before @After fun resetMirror() { Mirror.active = false }

    private val keptKeys = setOf(
        "settings.proxyOn", "settings.proxy", "settings.overlaySeen", "settings.overGameNative", "settings.nativeSeen",
        "content.checkedAt", "content.mirror",
    )

    private fun fillers(g: AppGraph): Map<String, () -> Unit> = mapOf(
        "scan.lastScan" to { g.scan.lastScan = 1_761_000_000_000L },
        "scan.lastDiff" to { g.scan.lastDiff = ScanDiff(listOf(1), listOf(2), listOf(3), listOf(4), listOf("Zio")) },
        "scan.decodeSec" to { g.scan.decodeSec = 17 },
        "heroes.tiers" to { g.heroes.tiers = mapOf(1001L to 4, 1002L to 2) },
        "heroes.team" to { g.heroes.team = listOf(1002L) },
        "heroes.configs" to { g.heroes.configs[1001L] = HeroConfig().apply { stars = 4; artifact = "art"; eeValue = 9.5 } },
        "pool.tiers" to { g.pool.tiers = setOf(1, 2) },
        "pool.extra" to { g.pool.extra = setOf(7L, 8L) },
        "pool.skip" to { g.pool.skip = setOf(9L) },
        "pool.losses" to { g.pool.losses = PoolLosses.SMALL },
        "pool.effort" to { g.pool.effort = PoolEffort.REBUILD },
        "pool.secondsPerHero" to { g.pool.secondsPerHero = 6.5 },
        "pool.pending" to { g.pool.pending = 123L },
        "pool.lastPool" to { g.pool.lastPool = 456L to 77 },
        "marks.cleared" to { g.marks.markReviewed(21L, false); g.marks.markReviewed(22L, false) },
        "marks.kept" to { g.marks.markReviewed(31L, true) },
        "marks.sold" to { g.marks.recordSold(41, 99, true); g.marks.recordSold(42, 99, false) },
        "sellReview.query" to { g.sellReview.edit(SellQuery.of(JSONObject().put("maximumChance", 7.5))) },
        "findGear.query" to { g.findGear.edit(PotentialQuery.of(JSONObject().put("show", 2).put("minimumChance", "12"))) },
        "improveHeroes.minGain" to { g.improveHeroes.minGain = 1.5 },
        "improveHeroes.rebuilds" to { g.improveHeroes.rebuilds = true },
        "content.checkedAt" to { g.content.checkedAt = 99L },
        "content.mirror" to { Mirror.active = true },
        "settings.consent" to { g.settings.consent = true },
        "settings.proxyOn" to { g.settings.proxyOn = true },
        "settings.proxy" to { g.settings.proxy = "1.2.3.4:80" },
        "settings.showRating" to { g.settings.showRating = Rating.EHP },
        "settings.overlaySeen" to { g.settings.overlaySeen = true },
        "settings.overGameNative" to { g.settings.overGameNative = true },
        "settings.nativeSeen" to { g.settings.nativeSeen = true },
    )

    private fun same(a: Any?, b: Any?) = when (a) {
        is JSONObject -> b is JSONObject && a.similar(b)
        is JSONArray -> b is JSONArray && a.similar(b)
        else -> a == b
    }

    private fun fieldsOf(g: AppGraph): JSONObject = g.store.backup().getJSONObject("fields")

    private fun fillFiles(g: AppGraph) {
        g.store.registeredFiles.forEach { it.write("content of ${it.name}") }
        g.store.registeredDirs.forEach { File(it.dir, "x.json").also { f -> f.parentFile.mkdirs() }.writeText("{}") }
    }

    @Test fun everyRegisteredFieldAndFileSurvivesBackupEraseRestore() = runTest {
        val env = Env(this)
        val g = env.g
        val fill = fillers(g)
        assertEquals("a new saved field needs a filler here", fill.keys, g.store.fieldKeys)
        val defaults = fieldsOf(Env(this).g)
        fill.values.forEach { it() }
        fillFiles(g)
        val filled = fieldsOf(g)
        fill.keys.forEach { assertFalse("$it was not changed from its default", same(filled.opt(it), defaults.opt(it))) }
        val backup = JSONObject(g.store.backup().toString())
        assertEquals(2, backup.getInt("version"))

        g.store.erase()
        env.disk.flush()
        val erased = fieldsOf(g)
        fill.keys.forEach {
            val want = if (it in keptKeys) filled.opt(it) else defaults.opt(it)
            assertTrue("$it after erase", same(erased.opt(it), want))
        }
        g.store.registeredFiles.forEach {
            assertEquals("${it.name} after erase", it.keepOnErase, it.exists)
        }
        assertEquals(listOf("content.json"), g.store.registeredFiles.filter { it.exists }.map { it.name })
        g.store.registeredDirs.forEach { assertFalse(it.dir.exists()) }
        assertEquals(setOf("content.json", "state.json"), env.dir.list()!!.toSet())

        g.store.restore(backup)
        val restored = fieldsOf(g)
        fill.keys.forEach { assertTrue("$it after restore", same(restored.opt(it), filled.opt(it))) }
        g.store.registeredFiles.forEach {
            if (it.backup) assertEquals(it.name, "content of ${it.name}", it.read()) else assertEquals("${it.name} is not in backups", it.keepOnErase, it.exists)
        }
    }

    @Test fun stateSurvivesRestart() = runTest {
        val env = Env(this)
        fillers(env.g).values.forEach { it() }
        env.g.store.saveNow()
        env.disk.flush()
        val again = Env(this, env.dir)
        again.g.store.load()
        assertTrue(same(fieldsOf(env.g), fieldsOf(again.g)))
    }

    @Test fun aV2StateFileWithAMakeRoomResultStillLoads() = runTest {
        val env = Env(this)
        val fields = JSONObject().put("marks.cleanup", JSONObject().put("scan", 5).put("picks", JSONArray())).put("marks.cleared", JSONArray(listOf(21, 22)))
        env.disk.write("state.json", JSONObject().put("version", 2).put("fields", fields).toString())
        env.g.loadOnce()
        assertEquals(setOf(21L, 22L), env.g.marks.cleared)
        env.g.store.saveNow()
        assertFalse(env.text("state.json")!!.contains("marks.cleanup"))
    }

    private fun stateV1() = javaClass.getResource("/state_v1.json")!!.readText()
    private fun backupV1() = javaClass.getResource("/backup_v1.json")!!.readText()

    private fun assertV1Fields(g: AppGraph) {
        assertEquals(1_761_000_000_000L, g.scan.lastScan)
        assertEquals(42, g.scan.decodeSec)
        g.scan.lastDiff!!.let {
            assertEquals(listOf(101L, 102L), it.newItems); assertEquals(listOf(103L), it.removedItems)
            assertEquals(listOf(104L), it.enhanced); assertEquals(listOf(105L, 106L), it.moved); assertEquals(listOf("Zio"), it.newHeroes)
        }
        assertEquals(mapOf(1001L to 4, 1002L to 2), g.heroes.tiers)
        assertEquals(listOf(1002L), g.heroes.team)
        assertEquals(setOf(1001L, 1002L), g.heroes.configs.keys)
        g.heroes.configs.getValue(1001L).let {
            assertEquals(5, it.stars); assertEquals("a_one", it.artifact); assertEquals(12.5, it.eeValue!!, 0.0)
            assertEquals(1.5, it.bonus[0], 0.0); assertEquals("Raid", it.saved.single().name); assertEquals(listOf(11L, 12L, 13L), it.saved.single().items)
        }
        assertTrue(g.settings.consent); assertTrue(g.settings.proxyOn); assertEquals("127.0.0.1:8080", g.settings.proxy)
        assertEquals(Rating.EHP, g.settings.showRating)
        assertTrue(g.settings.overlaySeen); assertTrue(g.settings.overGameNative); assertTrue(g.settings.nativeSeen)
        assertEquals(setOf(5, 2), g.pool.tiers); assertEquals(setOf(1003L, 1004L), g.pool.extra); assertEquals(setOf(1005L), g.pool.skip)
        assertEquals(PoolLosses.SMALL, g.pool.losses); assertEquals(PoolEffort.REBUILD, g.pool.effort)
        assertEquals(4.75, g.pool.secondsPerHero, 0.0); assertEquals(1_760_999_999_000L, g.pool.pending)
        assertEquals(1_760_999_000_000L to 88, g.pool.lastPool)
        assertEquals(1_760_000_000_000L, g.content.checkedAt); assertTrue(g.content.mirror)
        assertEquals(setOf(21L, 22L, 23L), g.marks.cleared); assertEquals(setOf(31L, 32L), g.marks.kept)
        assertEquals(listOf(MarksFeature.Sale(41, 1_761_000_000_000L, true), MarksFeature.Sale(42, 1_761_000_000_000L, false)), g.marks.sold)
        assertEquals(2, g.findGear.query.show); assertEquals("12", g.findGear.query.minimumChance); assertFalse(g.findGear.query.rareOnly)
        assertEquals(7.5, g.sellReview.query.maximumChance, 0.0); assertEquals(3, g.sellReview.query.minimumEnhance)
        assertEquals(1.25, g.improveHeroes.minGain, 0.0); assertTrue(g.improveHeroes.rebuilds)
    }

    @Test fun v1StateFileLoadsEveryKeyAndIsKeptAsACopy() = runTest {
        val env = Env(this)
        env.disk.write("state.json", stateV1())
        env.g.store.load()
        assertV1Fields(env.g)
        assertEquals(stateV1(), env.text("state.json.v1"))
        env.g.store.saveNow()
        assertEquals(2, JSONObject(env.text("state.json")!!).getInt("version"))
        env.disk.flush()
        val again = Env(this, env.dir)
        again.g.store.load()
        assertV1Fields(again.g)
        again.g.store.erase()
        again.disk.flush()
        assertFalse(File(env.dir, "state.json.v1").exists())
    }

    @Test fun v1PriorityListBecomesTierThreeAndTeamKeepsFirstHero() = runTest {
        val env = Env(this)
        env.disk.write("state.json", javaClass.getResource("/state_v1_priority.json")!!.readText())
        env.g.store.load()
        assertEquals(mapOf(7L to 3, 8L to 3, 9L to 3), env.g.heroes.tiers)
        assertEquals(listOf(8L), env.g.heroes.team)
    }

    @Test fun v1BackupRestoresFieldsBoxHistoryAndTracker() = runTest {
        val env = Env(this)
        env.g.store.restore(JSONObject(backupV1()))
        assertV1Fields(env.g)
        val src = JSONObject(backupV1())
        assertTrue(src.getJSONObject("box").similar(JSONObject(env.g.scan.boxFile.read()!!)))
        assertTrue(src.getJSONArray("history").similar(JSONArray(env.g.history.file.read()!!)))
        assertTrue(src.getJSONObject("upgrades").similar(JSONObject(env.g.tracker.upgrades.read()!!)))
        assertEquals(2, JSONArray(env.g.history.file.read()!!).length())
    }

    @Test fun oldBackupWithoutHistoryOrUpgradesLeavesHistoryAndClearsTracker() = runTest {
        val env = Env(this)
        env.g.history.file.write("""[{"at":1}]""")
        env.g.tracker.upgrades.write("""{"signature":"old"}""")
        env.g.store.restore(JSONObject(javaClass.getResource("/backup_v1_old.json")!!.readText()))
        assertV1Fields(env.g)
        assertEquals("""[{"at":1}]""", env.g.history.file.read())
        assertFalse(env.g.tracker.upgrades.exists)
        assertTrue(env.g.scan.boxFile.exists)
    }

    @Test fun backupWithoutBoxLeavesTheCurrentBox() = runTest {
        val env = Env(this)
        env.g.scan.boxFile.write("""{"keep":1}""")
        val j = JSONObject(backupV1()).also { it.remove("box") }
        env.g.store.restore(j)
        assertEquals("""{"keep":1}""", env.g.scan.boxFile.read())
    }

    @Test fun realBackupRestoresWithItsCounts() = runTest {
        val path = System.getenv("E7_BACKUP")
        assumeTrue("E7_BACKUP not set", !path.isNullOrBlank())
        val src = JSONObject(File(path!!).readText())
        val state = src.getJSONObject("state")
        val env = Env(this)
        env.g.store.restore(src)
        assertEquals(state.optJSONObject("configs")?.length() ?: 0, env.g.heroes.configs.size)
        assertEquals(state.optJSONObject("tiers")?.length() ?: 0, env.g.heroes.tiers.size)
        assertEquals(state.optJSONArray("sellKept")?.length() ?: 0, env.g.marks.kept.size)
        assertEquals(state.optJSONArray("clearedIds")?.length() ?: 0, env.g.marks.cleared.size)
        assertEquals(state.optJSONArray("overlaySales")?.length() ?: 0, env.g.marks.sold.size)
        assertEquals(state.optJSONArray("poolExtra")?.length() ?: 0, env.g.pool.extra.size)
        assertEquals(src.optJSONArray("history")?.length() ?: 0, JSONArray(env.g.history.file.read() ?: "[]").length())
        assertEquals(src.optJSONObject("upgrades")?.optJSONArray("records")?.length() ?: 0,
            JSONObject(env.g.tracker.upgrades.read() ?: "{}").optJSONArray("records")?.length() ?: 0)
        assertEquals(src.optJSONObject("box")?.optJSONArray("items")?.length() ?: 0,
            JSONObject(env.g.scan.boxFile.read() ?: "{}").optJSONArray("items")?.length() ?: 0)
        println("E7_BACKUP counts: configs=${env.g.heroes.configs.size} tiers=${env.g.heroes.tiers.size} kept=${env.g.marks.kept.size} " +
            "cleared=${env.g.marks.cleared.size} sold=${env.g.marks.sold.size} history=${JSONArray(env.g.history.file.read() ?: "[]").length()}")
    }
}
