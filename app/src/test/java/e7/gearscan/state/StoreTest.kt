package e7.gearscan.state

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreTest {
    enum class Mode { A, B, C }
    data class Q(val n: Int)

    private class Fixture(scope: TestScope, version: Int = 1, migrations: List<Migration> = emptyList()) {
        val dir: File = createTempDirectory("store").toFile()
        val disk = Disk(dir)
        val store = Store(disk, version, migrations, scope)
        var bool by store.field("bool", false, Codecs.bool)
        var int by store.field("int", 1, Codecs.int)
        var long by store.field("long", 2L, Codecs.long)
        var double by store.field("double", 3.0, Codecs.double)
        var string by store.field("string", "x", Codecs.string)
        var mode by store.field("mode", Mode.A, Codecs.enum(Mode.entries.toTypedArray()))
        var longSet by store.field("longSet", emptySet<Long>(), Codecs.longSet)
        var intSet by store.field("intSet", emptySet<Int>(), Codecs.intSet)
        var longList by store.field("longList", emptyList<Long>(), Codecs.longList)
        var obj by store.field("obj", Q(0), Codecs.obj({ JSONObject().put("n", it.n) }, { Q(it.getInt("n")) }))
        var kept by store.field("kept", "default", Codecs.string, keepOnErase = true)
        val backed = store.file("f/backed.json", backup = true)
        val local = store.file("local.json", backup = false)
        val keptFile = store.file("kept.json", backup = true, keepOnErase = true)

        fun fill() {
            bool = true; int = 7; long = 8_000_000_000L; double = 2.5; string = "hello"; mode = Mode.C
            longSet = setOf(5_000_000_000L, 2L); intSet = setOf(3, 4); longList = listOf(9L, 1L, 9L); obj = Q(42); kept = "mine"
            backed.write("B"); local.write("L"); keptFile.write("K")
        }

        fun assertFilled() {
            assertEquals(true, bool); assertEquals(7, int); assertEquals(8_000_000_000L, long); assertEquals(2.5, double, 0.0)
            assertEquals("hello", string); assertEquals(Mode.C, mode); assertEquals(setOf(5_000_000_000L, 2L), longSet)
            assertEquals(setOf(3, 4), intSet); assertEquals(listOf(9L, 1L, 9L), longList); assertEquals(Q(42), obj); assertEquals("mine", kept)
        }
    }

    @Test fun everyFieldTypeSurvivesBackupEraseRestore() = runTest {
        val f = Fixture(this)
        f.fill()
        val backup = JSONObject(f.store.backup().toString())
        f.store.erase()
        f.store.restore(backup)
        f.assertFilled()
        assertEquals("B", f.backed.read())
        assertNull(f.local.read())
        assertEquals("K", f.keptFile.read())
    }

    @Test fun eraseResetsFieldsAndDeletesFilesExceptKept() = runTest {
        val f = Fixture(this)
        f.fill()
        f.store.erase()
        assertEquals(false, f.bool); assertEquals(1, f.int); assertEquals(2L, f.long); assertEquals(3.0, f.double, 0.0)
        assertEquals("x", f.string); assertEquals(Mode.A, f.mode); assertTrue(f.longSet.isEmpty()); assertTrue(f.intSet.isEmpty())
        assertTrue(f.longList.isEmpty()); assertEquals(Q(0), f.obj)
        assertEquals("mine", f.kept)
        assertFalse(f.backed.exists); assertFalse(f.local.exists); assertTrue(f.keptFile.exists)
        val reloaded = Fixture(this).also { f.disk.flush(); it.disk.dir.let { d -> f.dir.copyRecursively(d, true) } }
        reloaded.store.load()
        assertEquals("mine", reloaded.kept); assertEquals(1, reloaded.int)
    }

    @Test fun loadReadsWhatWasSaved() = runTest {
        val f = Fixture(this)
        f.fill()
        f.store.saveNow()
        val g = Fixture(this)
        f.disk.flush(); f.dir.copyRecursively(g.dir, true)
        g.store.load()
        g.assertFilled()
    }

    private val v1ToV2 = Migration(1) { j ->
        val fields = j.getJSONObject("fields")
        fields.put("int", fields.getInt("old") * 10)
        j
    }

    @Test fun migrationRunsOnLoadAndRestore() = runTest {
        val f = Fixture(this, version = 2, migrations = listOf(v1ToV2))
        f.disk.write("store.json", """{"version":1,"fields":{"old":4,"string":"kept"}}""")
        f.store.load()
        assertEquals(40, f.int); assertEquals("kept", f.string)

        val g = Fixture(this, version = 2, migrations = listOf(v1ToV2))
        g.store.restore(JSONObject("""{"app":"e7gear","version":1,"fields":{"old":6},"files":{"f/backed.json":"zz"}}"""))
        assertEquals(60, g.int)
        assertEquals("zz", g.backed.read())
        assertEquals(2, g.store.backup().getInt("version"))
    }

    @Test fun writeIsAtomic() = runTest {
        val f = Fixture(this)
        f.disk.write("a.json", "old"); f.disk.flush()
        f.disk.beforeRename = { assertEquals("old", f.disk.read("a.json")); throw IllegalStateException("killed") }
        try { f.disk.write("a.json", "new"); f.disk.flush(); fail() } catch (_: IllegalStateException) {}
        assertEquals("old", f.disk.read("a.json"))
        f.disk.beforeRename = null
        f.disk.write("a.json", "new"); f.disk.flush()
        assertEquals("new", f.disk.read("a.json"))
        assertFalse(File(f.dir, "a.json.tmp").exists())
    }

    @Test fun debounceSavesOnceAfterQuickSets() = runTest(StandardTestDispatcher()) {
        val f = Fixture(this)
        val count = java.util.concurrent.atomic.AtomicInteger()
        fun writes(): Int { f.disk.flush(); return count.get() }
        f.disk.beforeRename = { count.incrementAndGet() }
        f.int = 1; f.int = 2; advanceTimeBy(300); f.int = 3; advanceTimeBy(300)
        assertEquals(0, writes())
        advanceTimeBy(300)
        assertEquals(1, writes())
        assertEquals(3, JSONObject(f.disk.read("store.json")!!).getJSONObject("fields").getInt("int"))
    }
}
