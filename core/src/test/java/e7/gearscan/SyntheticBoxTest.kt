package e7.gearscan

import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.Slot
import e7.gearscan.opt.Finish
import e7.gearscan.testing.SyntheticBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticBoxTest {
    private val made = SyntheticBox.generate(1)
    private val box = Box.parse(made.gearText)

    @Test
    fun theSameSeedGivesTheSameAccount() {
        val again = SyntheticBox.generate(1)
        assertEquals(made.gearText, again.gearText)
        assertEquals(made.backupText, again.backupText)
        assertTrue(made.gearText != SyntheticBox.generate(2).gearText)
    }

    @Test
    fun coversEverySlotSetMainStatAndStep() {
        val sets =
            TestContent.content.sets
                .map { it.key }
                .toSet()
        assertEquals(6 * SyntheticBox.PIECES_PER_SLOT, box.items.size)
        assertEquals(sets, box.items.map { it.set }.toSet())
        for (slot in Slot.entries) {
            val of = box.items.filter { it.slot == slot }
            assertEquals(SyntheticBox.mainsOf(slot).toSet(), of.map { it.mainType }.toSet())
            assertEquals((0..15).toSet(), of.map { it.enhance }.toSet())
        }
        assertEquals(setOf(85, 88, 90), box.items.map { it.level }.toSet())
        assertEquals(setOf("Epic", "Heroic"), box.items.map { it.rank }.toSet())
        assertTrue(box.items.any { it.locked })
    }

    @Test
    fun substatsFollowTheRollRules() {
        for (it in box.items) {
            val lines = if (it.rank == "Heroic" && it.enhance < 12) 3 else 4
            assertEquals(lines, it.subs.size)
            assertEquals(
                lines,
                it.subs
                    .map { s -> s.type }
                    .toSet()
                    .size,
            )
            assertTrue(it.subs.none { s -> s.type == it.mainType })
            assertEquals((if (it.rank == "Heroic") 3 else 4) + it.enhance / 3, it.subs.sumOf { s -> s.rolls })
            for (s in it.subs) {
                val r = Finish.range(s.type, it.level)
                assertTrue(s.value >= s.rolls * r.first && s.value <= s.rolls * Finish.maximumRoll(s.type, it.level))
            }
        }
        assertTrue(box.items.count { it.enhance == 15 && it.subs.any { s -> s.type == GearStat.SPD && s.value >= 20 } } >= 5)
    }

    @Test
    fun heroesWearGearAndHaveSetups() {
        val content = TestContent.content
        assertEquals(SyntheticBox.HEROES, box.allHeroes.size)
        assertTrue(box.allHeroes.all { content.heroes.containsKey(it.code) })
        assertTrue(box.heroes.count { box.gearOf(it.id).size == 6 } >= 20)
        val backup = JSONObject(made.backupText)
        assertEquals("e7gear", backup.getString("app"))
        assertEquals(SyntheticBox.HEROES, backup.getJSONObject("state").getJSONObject("configs").length())
        assertEquals(box.items.size, Box.parse(backup.getJSONObject("box").toString()).items.size)
        assertTrue(backup.getJSONArray("history").length() >= 2)
    }
}
