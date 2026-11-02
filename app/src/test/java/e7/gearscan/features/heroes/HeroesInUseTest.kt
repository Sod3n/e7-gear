package e7.gearscan.features.heroes

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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroesInUseTest {
    @After fun resetMirror() { Mirror.active = false }

    private fun graph(scope: CoroutineScope): AppGraph {
        val dir = createTempDirectory("inuse").toFile()
        File(dir, "content.json").writeText(TestContent.text)
        return AppGraph(Disk(dir), scope).also {
            it.heroes.presetNetwork = false
            it.scan.import(SyntheticBox.generate(1).gearText)
        }
    }

    private fun worn(g: AppGraph, ids: Collection<Long>) = g.scan.box!!.items.filter { it.equippedOn in ids }.map { it.id }.toSet()

    @Test fun inUseIsThePiecesWornByThePoolRunsHeroes() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val b = g.scan.box!!
        b.heroes.filter { g.heroes.gameHero(it) != null && b.gearOf(it.id).isNotEmpty() }.take(2).forEach { g.heroes.setTier(it.id, 5) }
        val members = g.pool.members().map { it.id }
        assertTrue(members.isNotEmpty())
        val expected = worn(g, members)
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, g.heroes.inUse(b))
    }

    @Test fun inUseChangesWhenThePoolTiersChange() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val b = g.scan.box!!
        g.pool.tiers = emptySet()
        g.pool.extra = emptySet()
        assertTrue(g.heroes.inUse(b).isEmpty())

        val hero = b.heroes.first { b.gearOf(it.id).isNotEmpty() && g.heroes.gameHero(it) != null }.id
        g.heroes.setTier(hero, 4)
        g.pool.tiers = setOf(4)
        assertEquals(worn(g, g.pool.members().map { it.id }), g.heroes.inUse(b))
        assertTrue(worn(g, listOf(hero)).all { it in g.heroes.inUse(b) })

        g.pool.tiers = setOf(5)
        assertTrue(worn(g, listOf(hero)).none { it in g.heroes.inUse(b) })
    }

    @Test fun anExtraHeroPutsItsPiecesInUseWhateverItsTier() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val b = g.scan.box!!
        g.pool.tiers = emptySet()
        val hero = b.heroes.first { b.gearOf(it.id).isNotEmpty() && g.heroes.gameHero(it) != null }.id
        g.pool.extra = setOf(hero)
        assertEquals(worn(g, listOf(hero)), g.heroes.inUse(b))
    }
}
