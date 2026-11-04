package e7.gearscan.features.heroes

import e7.gearscan.AppGraph
import e7.gearscan.TestContent
import e7.gearscan.state.Disk
import e7.gearscan.testing.SyntheticBox
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroesFeatureTest {

    private val made = SyntheticBox.generate(1)

    private fun graph(scope: CoroutineScope, dir: File = createTempDirectory("heroes").toFile()): AppGraph {
        File(dir, "content.json").writeText(TestContent.text)
        return AppGraph(Disk(dir), scope).also { it.heroes.presetNetwork = false }
    }

    private fun owners(g: AppGraph) = g.scan.box!!.items.associate { it.id to it.equippedOn }

    @Test fun equipThenUndoRestoresTheBox() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(made.gearText)
        val box = g.scan.box!!
        val hero = box.heroes.first { box.gearOf(it.id).size == 6 }.id
        val worn = box.gearOf(hero)
        val slot = worn.keys.first()
        val incoming = box.items.first { it.slot == slot && it.equippedOn != hero }
        val setup = g.heroes.setup(hero)!!
        val build = setup.build(worn.filterKeys { it != slot }.values.toList() + incoming)
        val before = owners(g)
        val rev = g.rev.value

        val prev = g.heroes.equip(hero, build)

        assertEquals(hero, g.scan.box!!.itemsById.getValue(incoming.id).equippedOn)
        assertEquals(null, g.scan.box!!.itemsById.getValue(worn.getValue(slot).id).equippedOn)
        assertTrue(g.rev.value > rev)
        g.heroes.undoEquip(prev)
        assertEquals(before, owners(g))
        g.disk.flush()
        assertEquals(before, e7.gearscan.data.Box.parse(g.scan.boxFile.read()!!).items.associate { it.id to it.equippedOn })
    }

    @Test fun presetCacheWritesGoThroughTheDiskQueueAndReadsWaitForThem() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        g.disk.beforeRename = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        g.heroes.presetCache.write("probe.json", "{}")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertFalse(File(g.heroes.builds.dir, "probe.json").exists())
        release.countDown()
        assertEquals("{}", g.heroes.presetCache.read("probe.json"))
        assertNotNull(g.heroes.presetCache.modified("probe.json"))
        g.heroes.presetCache.delete("probe.json")
        assertEquals(null, g.heroes.presetCache.read("probe.json"))
    }

    @Test fun presetsLoadFromTheBuildsCacheWithoutTheNetwork() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(made.gearText)
        made.builds.forEach { (code, text) -> g.heroes.presetCache.write("$code.json", text) }
        val hero = g.scan.box!!.heroes.first { made.builds.containsKey(it.code) }
        g.heroes.loadPresetsQuietly(hero.id, network = false)
        withContext(Dispatchers.Default) {
            // the loading flag clears just after the presets land, on the loader's thread
            withTimeout(20_000) { while (g.heroes.presets[hero.code] == null || g.heroes.presetLoading[hero.code] == true) Thread.sleep(20) }
        }
        assertTrue(g.heroes.presets.getValue(hero.code)!!.mostUsed.isNotEmpty())
        assertFalse(g.heroes.presetLoading[hero.code] == true)
    }

    @Test fun eraseDropsThePresetsAndTheBuildsCache() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this)
        g.scan.import(made.gearText)
        g.heroes.presetCache.write("x.json", "{}")
        g.heroes.presets["x"] = null
        g.scan.erase(e7.gearscan.userConfirmed())
        g.disk.flush()
        assertTrue(g.heroes.presets.isEmpty())
        assertFalse(File(g.heroes.builds.dir, "x.json").exists())
    }
}
