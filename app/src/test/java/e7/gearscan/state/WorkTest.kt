package e7.gearscan.state

import e7.gearscan.ui.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkTest {
    private fun host(s: TestScope, fg: ForegroundHost = NoForeground) = WorkHost(s, fg)

    @Test fun cancelledRunNeverPublishes() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val w = Work<Int>("w", h, Weight.Light)
        val gate = CompletableDeferred<Unit>()
        w.start { gate.await(); 1 }
        assertTrue(w.busy)
        w.cancel()
        gate.complete(Unit)
        assertNull(w.result)
        assertFalse(w.busy)
    }

    @Test fun uncooperativeRunThatFinishesAfterCancelNeverPublishes() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val w = Work<Int>("w", h, Weight.Light)
        w.start { w.cancel(); 5 }
        assertNull(w.result)
    }

    @Test fun startingTwiceKeepsOnlyTheSecondResult() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val w = Work<Int>("w", h, Weight.Light)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        w.start { first.await(); 1 }
        w.start { second.await(); 2 }
        first.complete(Unit)
        assertNull(w.result)
        second.complete(Unit)
        assertEquals(2, w.result)
        assertFalse(w.busy)
        assertTrue(h.active.isEmpty())
    }

    @Test fun secondHeavyStartIsRefusedNamingTheFirst() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val a = Work<Int>("Sell review", h, Weight.Heavy)
        val b = Work<Int>("Pool", h, Weight.Heavy)
        a.start { CompletableDeferred<Unit>().await(); 1 }
        assertEquals(Outcome.Failed("Finish or stop Sell review first"), b.start { 2 })
        assertFalse(b.busy)
        assertTrue(a.busy)
        assertEquals(Outcome.Done, a.start { 3 })
        assertEquals(3, a.result)
    }

    @Test fun heavyStartCancelsYieldingRun() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val y = Work<Int>("check", h, Weight.Yielding)
        val heavy = Work<Int>("pool", h, Weight.Heavy)
        var cancelled = false
        y.start { onCancel { cancelled = true }; CompletableDeferred<Unit>().await(); 1 }
        assertEquals(Outcome.Done, heavy.start { CompletableDeferred<Unit>().await(); 2 })
        assertFalse(y.busy)
        assertTrue(cancelled)
        assertEquals(Outcome.Failed("Finish or stop pool first"), y.start { 1 })
        h.cancelAll()
    }

    @Test fun cancelAllStopsEverythingAndRunsOnCancel() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val a = Work<Int>("a", h, Weight.Light)
        val b = Work<Int>("b", h, Weight.Heavy)
        val ran = mutableListOf<String>()
        a.start { onCancel { ran += "a" }; CompletableDeferred<Unit>().await(); 1 }
        b.start { onCancel { ran += "b" }; CompletableDeferred<Unit>().await(); 1 }
        assertEquals(2, h.active.size)
        h.cancelAll()
        assertTrue(h.active.isEmpty())
        assertFalse(a.busy || b.busy)
        assertEquals(setOf("a", "b"), ran.toSet())
    }

    @Test fun onCancelDoesNotRunAfterNormalFinish() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val w = Work<Int>("w", h, Weight.Light)
        var ran = false
        w.start { onCancel { ran = true }; 1 }
        w.cancel()
        assertFalse(ran)
        assertEquals(1, w.result)
    }

    @Test fun foregroundHostSeesTheRunningHeavyWork() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String?>()
        val h = host(this, object : ForegroundHost { override fun shown(work: Work<*>?) { seen += work?.name } })
        val w = Work<Int>("pool", h, Weight.Heavy)
        w.start { 1 }
        assertEquals(listOf("pool", null), seen)
    }

    @Test fun failureIsRecordedNotPublished() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val w = Work<Int>("w", h, Weight.Light)
        w.start { error("boom") }
        assertNull(w.result)
        assertEquals("boom", w.error)
        assertFalse(w.busy)
    }

    @Test fun yieldingForegroundWorkIsShownUntilAHeavyOneStarts() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String?>()
        val h = host(this, object : ForegroundHost { override fun shown(work: Work<*>?) { seen += work?.name } })
        val check = Work<Int>("check", h, Weight.Yielding, foreground = true)
        val pool = Work<Int>("pool", h, Weight.Heavy)
        val light = Work<Int>("light", h, Weight.Light)
        check.start { CompletableDeferred<Unit>().await(); 1 }
        light.start { CompletableDeferred<Unit>().await(); 1 }
        pool.start { CompletableDeferred<Unit>().await(); 2 }
        assertEquals(listOf("check", null, "pool"), seen)
        h.cancelAll()
        assertEquals(listOf("check", null, "pool", null), seen)
    }

    @Test fun notificationProgressIsThrottledToOncePerSecondForTheShownWork() = runTest(UnconfinedTestDispatcher()) {
        var now = 10_000L
        val texts = mutableListOf<String>()
        val fg = object : ForegroundHost {
            override fun shown(work: Work<*>?) {}
            override fun progress(work: Work<*>, text: String, done: Int, total: Int) { texts += text }
        }
        val h = WorkHost(this, fg) { now }
        val w = Work<Int>("pool", h, Weight.Heavy)
        val quiet = Work<Int>("curve", h, Weight.Light)
        val gate = CompletableDeferred<Unit>()
        quiet.start { progress(1, 2, "quiet"); gate.await(); 0 }
        w.start {
            progress(1, 10)
            now += 400; progress(2, 10, "two")
            now += 700; progress(3, 10, "three")
            gate.await(); 1
        }
        assertEquals(listOf("1 of 10", "three"), texts)
        assertEquals(0.3f, w.fraction)
        h.cancelAll()
    }

    @Test fun secondHeavyStartIsRefusedWhateverThePathToIt() = runTest(UnconfinedTestDispatcher()) {
        val h = host(this)
        val pool = Work<Int>("Pool run", h, Weight.Heavy)
        val sell = Work<Int>("Sell review", h, Weight.Heavy)
        val check = Work<Int>("Upgrade check", h, Weight.Yielding, foreground = true)
        pool.start { CompletableDeferred<Unit>().await(); 1 }
        assertEquals(Outcome.Failed("Finish or stop Pool run first"), sell.start { 2 })
        assertEquals(Outcome.Failed("Finish or stop Pool run first"), check.start { 3 })
        assertFalse(sell.busy || check.busy)
        h.cancelAll()
    }
}
