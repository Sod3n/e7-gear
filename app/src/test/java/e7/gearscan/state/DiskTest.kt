package e7.gearscan.state

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiskTest {
    private val dir: File = createTempDirectory("disk").toFile()
    private val disk = Disk(dir)

    @Test fun writesAndDeletesKeepTheirOrder() {
        disk.write("a", "A")
        disk.delete("a")
        disk.write("b", "B")
        disk.flush()
        assertFalse(File(dir, "a").exists())
        assertEquals("B", File(dir, "b").readText())
    }

    @Test fun aDeleteQueuedAfterAWriteWins() {
        repeat(50) { disk.write("a", "A$it") }
        disk.delete("a")
        disk.flush()
        assertFalse(File(dir, "a").exists())
    }

    @Test fun readsSeeQueuedWrites() {
        val gate = CountDownLatch(1)
        disk.beforeRename = { gate.await() }
        disk.write("a", "A")
        assertFalse(File(dir, "a").exists())
        gate.countDown()
        assertEquals("A", disk.read("a"))
    }

    @Test fun flushWaitsForTheWriter() {
        disk.beforeRename = { Thread.sleep(150) }
        disk.write("a", "A")
        assertFalse(File(dir, "a").exists())
        disk.flush()
        assertEquals("A", File(dir, "a").readText())
    }

    @Test fun appendsKeepTheirOrderAfterAReplace() {
        disk.write("log", "h\n")
        disk.append("log", "1\n")
        disk.append("log", "2\n")
        disk.flush()
        assertEquals("h\n1\n2\n", File(dir, "log").readText())
    }

    @Test fun eraseWhileAWriteIsPendingLeavesNoFileBehind() = runTest {
        val store = Store(disk, 1, emptyList(), this)
        val backed = store.file("f/backed.json", backup = true)
        val local = store.file("local.json", backup = false)
        val gate = CountDownLatch(1)
        disk.beforeRename = { gate.await() }
        backed.write("B")
        local.write("L")
        store.erase()
        gate.countDown()
        disk.flush()
        assertFalse(File(dir, "f/backed.json").exists())
        assertFalse(File(dir, "local.json").exists())
        assertNull(backed.read())
        assertTrue(File(dir, "store.json").exists())
    }
}
