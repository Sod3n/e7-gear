package e7.gearscan

import e7.gearscan.calc.Calc
import e7.gearscan.calc.Reforge
import e7.gearscan.data.Box
import e7.gearscan.data.GearStat
import e7.gearscan.data.HeroConfig
import e7.gearscan.data.Http
import e7.gearscan.data.Presets
import e7.gearscan.data.Request
import e7.gearscan.data.SellQuery
import e7.gearscan.data.Slot
import e7.gearscan.data.Stat
import e7.gearscan.opt.Finish
import e7.gearscan.opt.SearchRun
import e7.gearscan.opt.SellSearch
import e7.gearscan.opt.Setup
import e7.gearscan.testing.SyntheticBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                val level = if (it.level == 90) 85 else it.level
                val gain = if (it.level == 90) Reforge.gain(s.type, s.rolls) else 0.0
                val r = Finish.range(s.type, level)
                assertTrue(s.value >= s.rolls * r.first + gain && s.value <= s.rolls * Finish.maximumRoll(s.type, level) + gain)
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

    @Test
    fun sellReviewFindsWeakBagPiecesAndSafeguardsProtectOthers() {
        val pool =
            box.heroes
                .take(8)
                .map { it.id }
                .toSet()
        val ctx = SellSearch.Context(box, emptyMap(), 1, emptySet(), pool, emptyMap(), SellQuery())
        val out = SellSearch.run(ctx, null)
        assertTrue(out.matches.size >= 20)
        assertTrue(out.rows.count { it.protected } >= 5)
    }

    @Test
    fun auditedHeroesHavePresetsAndOnlyTheSpeedRuleBlocks() {
        val content = TestContent.content
        assertEquals(SyntheticBox.HEROES, made.builds.size)
        val dir =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        made.builds.forEach { (code, text) -> File(dir, "$code.json").writeText(text) }
        val tier = box.allHeroes.withIndex().associate { (i, h) -> h.id to 5 - i % 5 }
        val full = box.heroes.filter { box.gearOf(it.id).size == 6 }.take(12)
        for (h in full) {
            val tabs = Presets.load(Http(), dir, content, h.code, h.name, false)!!
            assertTrue(tabs.mostUsed.size >= 3 && tabs.topRanked.size >= 3 && tabs.fastest.size >= 3)
            val gh = content.heroes.getValue(h.code)
            for (p in tabs.mostUsed.take(1)) {
                val cfg = HeroConfig()
                val req = Presets.toRequest(p, Calc.heroBase(gh, cfg, p.artifact, content), null)

                fun builds(r: Request) =
                    SearchRun(Setup(h, gh, cfg, r, box, content, { tier.getValue(it) < tier.getValue(h.id) }), 1).anyBuild(2, 20_000)
                assertEquals("${h.name} ${p.label} unblocked", true, builds(req.copy().also { it.mins[Stat.SPD.ordinal] = Double.NaN }))
                assertEquals("${h.name} ${p.label} blocked by speed", false, builds(req.copy().also { it.mins[Stat.SPD.ordinal] = 999.0 }))
            }
        }
    }

    @Test
    fun theAuditedHeroSavedSetupIsMetByItsOwnGearAndOnlySpeedBlocks() {
        val content = TestContent.content
        val h = box.heroes.first { content.heroes.containsKey(it.code) && box.gearOf(it.id).size == 6 }
        val gh = content.heroes.getValue(h.code)
        val cfgJson = JSONObject(made.backupText).getJSONObject("state").getJSONObject("configs").getJSONObject(h.id.toString())
        val cfg = HeroConfig.fromJson(cfgJson)
        val tier = box.allHeroes.withIndex().associate { (i, x) -> x.id to 5 - i % 5 }

        fun found(r: Request) =
            SearchRun(Setup(h, gh, cfg, r, box, content, { tier.getValue(it) < tier.getValue(h.id) }), 1).anyBuild(2, 3_000)
        val saved = cfg.request!!
        assertEquals(true, found(saved.copy()))
        assertEquals(false, found(saved.copy().also { it.mins[Stat.SPD.ordinal] = 999.0 }))
        val others = saved.copy().also { it.mins[Stat.SPD.ordinal] = 999.0 }
        assertEquals(true, found(others.copy().also { it.mins[Stat.SPD.ordinal] = Double.NaN }))
    }
}
