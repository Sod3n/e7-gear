package e7.gearscan.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreshWorkTest {
    @Test fun staleFlipsExactlyWhenAnInputChanges() = runTest(UnconfinedTestDispatcher()) {
        var query = "a"
        val w = FreshWork<String, Int>("find", WorkHost(this), Weight.Light) { query }
        assertFalse(w.stale)
        w.start { key -> key.length + 10 }
        assertEquals(11, w.result)
        assertFalse(w.stale)
        query = "b"
        assertTrue(w.stale)
        query = "a"
        assertFalse(w.stale)
        query = "bb"
        w.start { key -> key.length + 10 }
        assertEquals(12, w.result)
        assertFalse(w.stale)
    }

    @Test fun keyIsCapturedAtStart() = runTest(UnconfinedTestDispatcher()) {
        var n = 1
        val w = FreshWork<Int, Int>("x", WorkHost(this), Weight.Light) { n }
        w.start { key -> n = 2; key }
        assertEquals(1, w.result)
        assertTrue(w.stale)
    }
}
