package e7.gearscan.features.pool

import e7.gearscan.AppGraph
import e7.gearscan.TestContent
import e7.gearscan.data.Mirror
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PoolFeatureTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("pool").toFile()): AppGraph {
        File(dir, "content.json").writeText(TestContent.text)
        return AppGraph(Disk(dir), scope)
    }

    private fun run(at: Long) = PoolState(emptyList(), emptyMap(), emptyMap(), emptyMap(), null, null, false, at, at)

    @Test fun theRunAndTheCallsResetWhenTheBoxChanges() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(SyntheticBox.generate(1).gearText)
        g.pool.state = run(1)
        g.pool.give = mapOf(5L to 6L)
        g.pool.keep = mapOf(7L to 8L)
        g.scan.import(SyntheticBox.generate(2).gearText)
        assertNull(g.pool.state)
        assertTrue(g.pool.give.isEmpty() && g.pool.keep.isEmpty())
    }

    @Test fun membersFollowTheTiersAndSkipsLeaveTheRun() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(SyntheticBox.generate(1).gearText)
        val ids = g.scan.box!!.heroes.map { it.id }
        ids.forEach { g.heroes.setTier(it, 5) }
        g.pool.tiers = setOf(5)
        val members = g.pool.members().map { it.id }
        assertTrue(members.isNotEmpty())
        g.pool.skip = setOf(members.first())
        assertEquals(members.drop(1), g.pool.heroes().map { it.id })
        g.pool.tiers = emptySet()
        assertTrue(g.pool.members().isEmpty())
        g.pool.extra = setOf(members.first())
        assertEquals(listOf(members.first()), g.pool.members().map { it.id })
    }

    @Test fun scoreWeighsMatchesByTier() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(SyntheticBox.generate(1).gearText)
        val (a, b) = g.scan.box!!.heroes.map { it.id }
        g.heroes.setTier(a, 4)
        g.heroes.setTier(b, 1)
        assertEquals(Math.round((4 * 100 + 1 * 50) / 5.0 * 100).toInt(), g.pool.score(mapOf(a to 100, b to 50, 9L to null)))
        assertNotNull(g.pool)
    }
}
