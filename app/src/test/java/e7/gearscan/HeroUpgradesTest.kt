package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class HeroUpgradesTest {
    private val content = Content("test", JSONObject().put("images", JSONObject().put("face", "").put("artifact", ""))
        .put("builds", "").put("heroes", JSONArray()).put("artifacts", JSONArray()).put("sets", JSONArray(listOf(
            JSONObject().put("key", "SpeedSet").put("name", "Speed").put("pieces", 4).put("icon", "").put("spdPct", 25),
            JSONObject().put("key", "HealthSet").put("name", "Health").put("pieces", 2).put("icon", "").put("hpPct", 15),
            JSONObject().put("key", "CriticalSet").put("name", "Critical").put("pieces", 2).put("icon", "").put("cr", 12),
            JSONObject().put("key", "HitSet").put("name", "Hit").put("pieces", 2).put("icon", "").put("eff", 20)))))
    private val hero = OwnedHero(99, "test", "Test hero", 6)
    private val game = GameHero("test", "Test hero", null, null, Stats8(1000.0, 5000.0, 500.0, 100.0, 15.0, 150.0, 0.0, 0.0), null, null, 0.0, emptyList())

    private fun main(slot: Slot) = when (slot) {
        Slot.WEAPON -> GearStat.ATK; Slot.HELMET -> GearStat.HP; Slot.ARMOR -> GearStat.DEF
        Slot.NECKLACE -> GearStat.CD; Slot.RING -> GearStat.HP_P; Slot.BOOTS -> GearStat.SPD
    }
    private fun mainValue(t: GearStat) = when (t) { GearStat.ATK -> 500.0; GearStat.HP -> 2700.0; GearStat.DEF -> 300.0; GearStat.SPD -> 40.0; else -> 60.0 }

    private fun piece(id: Long, slot: Slot, enhance: Int, subs: List<Pair<GearStat, Double>>, set: String = "SpeedSet", worn: Boolean = false): Item {
        val m = main(slot)
        val atNow = if (enhance == 15) mainValue(m) else mainValue(m) / 5 * (1 + enhance / 3 * 0.6)
        return Item(id, slot, set, "Epic", 85, enhance, m, atNow, subs.map { (t, v) -> Substat(t, v, 1, false) }, false,
            if (worn) hero.id else null, main15 = mainValue(m))
    }

    private fun filler(slot: Slot) = listOf(GearStat.ATK_P, GearStat.DEF_P, GearStat.EFF, GearStat.RES).filter { it != main(slot) }

    /** Six worn +15 pieces with [spd] speed and [cr] crit each, Speed set. */
    private fun worn(spd: Double = 10.0, cr: Double = 5.0) = Slot.entries.mapIndexed { i, slot ->
        piece(10L + i, slot, 15, listOf(GearStat.SPD to spd, GearStat.CR to cr) + filler(slot).take(2).map { it to 4.0 }, worn = true)
    }

    private fun setup(items: List<Item>, req: Request): Setup {
        val b = Box(JSONObject().put("items", JSONArray(items.map(UpgradeRecord::itemJson))).put("heroes", JSONArray()))
        return Setup(hero, game, HeroConfig().apply { request = req }, req, b, content, { true })
    }

    private fun speedGoal() = Request().apply { goal = Goal.SPEED; capCrit = false; minEnhance = 0; lowerPriorityOnly = false; reforged = false }

    private fun speedOf(it: Item) = it.subs.firstOrNull { s -> s.type == GearStat.SPD }?.value ?: 0.0
    private fun critOf(it: Item) = it.subs.firstOrNull { s -> s.type == GearStat.CR }?.value ?: 0.0

    @Test fun aPieceThatCanOnlyGetFasterCountsExactlyTheFinishesFasterThanWhatTheHeroWears() {
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val r = HeroUpgrades().run(listOf(setup(worn() + candidate, speedGoal())), listOf(candidate))
        val faster = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS).count { speedOf(it) > 10.0 }
        assertTrue("the test needs both outcomes", faster in 1 until HeroUpgrades.SIMS)
        val pick = r.picks.single()
        assertEquals(faster, pick.values.size)
        assertEquals(0, pick.unknown)
        val base = r.bases.getValue(hero.id)
        assertFalse(base.timedOut)
        // the best finish replaces the worn helmet: its speed over the worn one is the whole gain
        val top = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS).maxOf(::speedOf)
        assertEquals(top - 10.0, pick.values.first() - base.value!!, 0.02)
        assertEquals(1L, pick.example.single { it == 1L }); assertEquals(top, speedOf(pick.finish!!), 0.0)
        // the speed goal scores speed alone, so the other stats the finish moved stay out of the line
        assertTrue(pick.changes, pick.changes.startsWith("Spd +") && !pick.changes.contains("·"))
    }

    @Test fun aPieceASameSetSpareAlwaysBeatsIsNotListed() {
        val spare = piece(2, Slot.HELMET, 15, listOf(GearStat.SPD to 40.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 3.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val r = HeroUpgrades().run(listOf(setup(worn() + spare + candidate, speedGoal())), listOf(candidate))
        assertTrue(r.picks.isEmpty())
        assertEquals(0, r.unknownSteps)
    }

    @Test fun aSpareOverTheCritCapDoesNotHideALegalUpgrade() {
        // worn: speed 225 (boots main and Speed set included), crit 15 + 6 x 14 = 99. The spare would take crit to 110, past the 105 cap: no build can use it.
        val req = Request().apply { goal = Goal.TARGETS; minEnhance = 0; lowerPriorityOnly = false; reforged = false
            targets[Stat.SPD.ordinal] = 250.0; weights[Stat.SPD.ordinal] = 3; targets[Stat.CR.ordinal] = 100.0; weights[Stat.CR.ordinal] = 1 }
        val spare = piece(2, Slot.HELMET, 15, listOf(GearStat.SPD to 30.0, GearStat.CR to 25.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0))
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.CR to 4.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0))
        val s = setup(worn(cr = 14.0) + spare + candidate, req)
        val r = HeroUpgrades().run(listOf(s), listOf(candidate))
        val finishes = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS)
        assertTrue("the spare has at least as much of every scored stat as every finish", finishes.all { speedOf(it) <= 30 && critOf(it) <= 25 })
        // only the helmet can change, so each finish's build is the worn gear with it in place of the worn helmet
        val obj = Objectives.of(req, s.hb); val maxs = s.maxs()
        fun value(items: List<Item>): Double? {
            val f = e7.gearscan.calc.Calc.final(s.hb, items, false, s.content)
            if ((0 until 8).any { !maxs[it].isNaN() && f.stats[it] > maxs[it] }) return null
            return obj.eval(f.stats + doubleArrayOf(f.gs), f.dmgMult, f.pen)
        }
        val wornGear = worn(cr = 14.0)
        val bar = value(wornGear)!! + HeroUpgrades.MIN_GAIN * HeroUpgrades.unit(s, null)
        val better = finishes.count { f -> value(wornGear.map { if (it.slot == Slot.HELMET) f else it })?.let { it > bar } == true }
        assertTrue("the test needs a legal better finish", better > 0)
        assertEquals(better, r.picks.single().values.size)
    }

    @Test fun withNoBuildAtAllAPieceThatMakesOnePossibleCounts() {
        val req = speedGoal().apply { mins[Stat.SPD.ordinal] = 235.0 }
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 12.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val r = HeroUpgrades().run(listOf(setup(worn() + candidate, req)), listOf(candidate))
        val base = r.bases.getValue(hero.id)
        assertNull(base.value); assertFalse(base.timedOut)
        // worn speed 225 with a 10-speed helmet: the finish needs 20 speed or more
        val enough = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS).count { speedOf(it) >= 20.0 }
        assertTrue("the test needs both outcomes", enough in 1 until HeroUpgrades.SIMS)
        assertEquals(enough, r.picks.single().values.size)
    }

    @Test fun anotherProtectedHerosPieceIsNotACandidateForThisHero() {
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val s = setup(worn() + candidate, speedGoal())
        val protectedElsewhere = Setup(s.hero, s.game, s.cfg, s.req, s.box, s.content, s.canTakeFrom, setOf(candidate.id))
        assertTrue(HeroUpgrades().run(listOf(protectedElsewhere), listOf(candidate)).picks.isEmpty())
    }

    @Test fun aResumedCheckKeepsEverySettledStepAndTheSameAnswer() {
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val s = setup(worn() + candidate, speedGoal())
        val progress = HeroUpgrades.Progress()
        val first = HeroUpgrades().run(listOf(s), listOf(candidate), resume = progress)
        val restored = HeroUpgrades.Progress.of(JSONObject(progress.toJson().toString()))
        var saves = 0
        val again = HeroUpgrades().run(listOf(s), listOf(candidate), resume = restored, saved = { saves++ })
        assertEquals(0, saves)
        assertEquals(first.picks.single().values, again.picks.single().values)
    }

    // ---- the engine's distinct slot ----

    private val subPool = listOf(GearStat.SPD, GearStat.CR, GearStat.CD, GearStat.ATK_P, GearStat.HP_P, GearStat.DEF_P, GearStat.EFF, GearStat.HP)
    private val sets = listOf("SpeedSet", "HealthSet", "CriticalSet", "HitSet")
    private fun randomPiece(rnd: Random, id: Long, slot: Slot, equipped: Boolean = false): Item {
        val types = subPool.filter { it != main(slot) }.shuffled(rnd).take(4)
        val subs = types.map { t -> t to when (t) { GearStat.SPD -> rnd.nextInt(2, 22); GearStat.CR -> rnd.nextInt(3, 26); GearStat.HP -> rnd.nextInt(150, 900)
            else -> rnd.nextInt(4, 33) }.toDouble() }
        return piece(id, slot, 15, subs, sets[rnd.nextInt(sets.size)], equipped)
    }

    @Test fun theDistinctSlotGivesEveryPieceTheBestBuildASearchWithOnlyThatPieceFinds() {
        val cases = listOf(
            Request().apply { goal = Goal.TARGETS; minEnhance = 0; lowerPriorityOnly = false; reforged = false
                targets[Stat.SPD.ordinal] = 230.0; weights[Stat.SPD.ordinal] = 3; targets[Stat.CR.ordinal] = 100.0; weights[Stat.CR.ordinal] = 2
                targets[Stat.HP.ordinal] = 12000.0; weights[Stat.HP.ordinal] = 1 },
            Request().apply { goal = Goal.DAMAGE; capCrit = false; minEnhance = 0; lowerPriorityOnly = false; reforged = false; mins[Stat.SPD.ordinal] = 150.0 },
            Request().apply { goal = Goal.TARGETS; minEnhance = 0; lowerPriorityOnly = false; reforged = false; setAlts = listOf(mapOf("SpeedSet" to 4, "CriticalSet" to 2))
                targets[Stat.SPD.ordinal] = 230.0; weights[Stat.SPD.ordinal] = 2; targets[Stat.CR.ordinal] = 100.0; weights[Stat.CR.ordinal] = 3 },
        )
        for ((n, req) in cases.withIndex()) for (seed in 0 until 3) {
            val rnd = Random(seed * 7 + n)
            var id = 100L
            val box = Slot.entries.flatMap { slot -> List(7) { k -> randomPiece(rnd, id++, slot, equipped = k == 0) } }
            val s = setup(box, req)
            val finishes = List(25) { randomPiece(rnd, -1L - it, Slot.HELMET) }
            val forced = Setup(s.hero, s.game, s.cfg, s.req, s.box, s.content, s.canTakeFrom, box.filter { it.slot == Slot.HELMET }.map { it.id }.toSet())
            fun alone(f: Item, bar: Double) = SearchRun(forced, 1, extra = listOf(f), startThreshold = bar).also { it.run(1, 60_000) }.builds().firstOrNull()?.value
            // a bar in the middle of what the finishes can do, so some beat it and some don't
            val bar = finishes.mapNotNull { alone(it, Double.NEGATIVE_INFINITY) }.sorted().let { it[it.size / 2] }
            val distinct = SearchRun(forced, finishes.size, extra = finishes, startThreshold = bar, distinctSlot = Slot.HELMET).also { it.run(1, 60_000) }
            val got = distinct.builds().associate { b -> b.items.first { it.slot == Slot.HELMET }.id to b.value }
            assertTrue(distinct.unsettled().isEmpty())
            val want = finishes.mapNotNull { f -> alone(f, bar)?.let { f.id to it } }.toMap()
            assertTrue("case $n seed $seed: the test needs both outcomes (${want.size}/25)", want.size in 1 until 25)
            assertEquals("case $n seed $seed", want.keys, got.keys)
            for ((k, v) in want) assertEquals("case $n seed $seed piece $k", v, got.getValue(k), 1e-9)
        }
    }

    @Test fun aDistinctSearchStoppedEarlyLeavesItsOpenPiecesUnsettled() {
        val rnd = Random(3)
        var id = 100L
        val box = Slot.entries.flatMap { slot -> List(7) { k -> randomPiece(rnd, id++, slot, equipped = k == 0) } }
        val s = setup(box, speedGoal())
        val forced = Setup(s.hero, s.game, s.cfg, s.req, s.box, s.content, s.canTakeFrom, box.filter { it.slot == Slot.HELMET }.map { it.id }.toSet())
        val finishes = List(10) { randomPiece(rnd, -1L - it, Slot.HELMET) }
        val r = SearchRun(forced, finishes.size, extra = finishes, distinctSlot = Slot.HELMET)
        r.cancel(); r.run(1, 60_000)
        assertEquals(finishes.map { it.id }.toSet(), r.unsettled().map { it.id }.toSet())
    }
}
