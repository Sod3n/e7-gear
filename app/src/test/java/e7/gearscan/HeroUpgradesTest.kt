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

    @Test fun aPieceASameSetSpareAlwaysBeatsGainsNothingOverTheBestBuild() {
        val spare = piece(2, Slot.HELMET, 15, listOf(GearStat.SPD to 40.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 3.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val r = HeroUpgrades().run(listOf(setup(worn() + spare + candidate, speedGoal())), listOf(candidate))
        // the spare is in the best build; only against the worn 10-speed helmet do the faster finishes count
        val pick = r.picks.single()
        assertTrue(pick.values.isEmpty())
        val finishes = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS)
        assertEquals(finishes.indices.filter { speedOf(finishes[it]) > 10.0 }.toSet(), pick.swaps.map { it.first }.toSet())
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

    @Test fun aFinishThatOnlyHelpsByMovingOtherPiecesCountsOnlyWithRebuilds() {
        // Speed 4 is required: four Speed pieces (weapon, helmet, armor, necklace) and two Hit pieces
        val gear = Slot.entries.mapIndexed { i, slot ->
            piece(10L + i, slot, 15, listOf(GearStat.SPD to 10.0, GearStat.CR to 5.0) + filler(slot).take(2).map { it to 4.0 },
                set = if (slot == Slot.RING || slot == Slot.BOOTS) "HitSet" else "SpeedSet", worn = true)
        }
        // a spare Speed ring one speed slower than the worn Hit ring, so the starting build is the worn one
        val spareRing = piece(30, Slot.RING, 15, listOf(GearStat.SPD to 9.0, GearStat.CR to 5.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0))
        val hitHelmet = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 12.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0), set = "HitSet")
        val req = speedGoal().apply { minEnhance = 12; setAlts = listOf(mapOf("SpeedSet" to 4)) }
        val s = setup(gear + spareRing + hitHelmet, req)
        val r = HeroUpgrades().run(listOf(s), listOf(hitHelmet))
        val pick = r.picks.single()
        // in its slot it breaks Speed 4; with the spare Speed ring moved in it is faster
        assertEquals(HeroUpgrades.SIMS, pick.values.size)
        assertTrue(pick.swaps.isEmpty())
        assertEquals(1, pick.moved)
        // tracked as it is (+0, speed 12): the rebuild with the spare ring already beats the worn build, so finishes must beat that
        val tracked = HeroUpgrades(120).piece(listOf(s), hitHelmet, java.util.concurrent.ConcurrentHashMap()).single()
        val finishes = HeroUpgrades.finishes(listOf(hitHelmet), 120)
        assertTrue(tracked.now)
        assertEquals(finishes.indices.filter { speedOf(finishes[it]) > 12.0 }.toSet(), tracked.hits.keys)
        assertTrue(tracked.swapHits.isEmpty()); assertFalse(tracked.swapNow)
        assertTrue(tracked.rebuildBuild!!.any { it.id == spareRing.id })

        // a Speed helmet with the same rolls fits the slot: it counts both ways, and the swap is the same build
        val speedHelmet = piece(2, Slot.HELMET, 0, hitHelmet.subs.map { it.type to it.value })
        val both = HeroUpgrades().run(listOf(setup(gear + speedHelmet, req)), listOf(speedHelmet)).picks.single()
        assertEquals(both.values.size, both.swaps.size)
        assertEquals(both.values, both.swaps.map { it.second })
    }

    @Test fun thisPieceOnlyIsMeasuredAgainstWhatTheHeroWearsNotTheBestBuild() {
        // the hero wears a 5-speed helmet; a 10-speed one waits in the bag, so the best build is faster than what it wears
        val gear = worn().map { if (it.slot == Slot.HELMET) piece(it.id, Slot.HELMET, 15, listOf(GearStat.SPD to 5.0, GearStat.CR to 5.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0), worn = true) else it }
        val spare = piece(30, Slot.HELMET, 15, listOf(GearStat.SPD to 10.0, GearStat.CR to 5.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0))
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 7.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val s = setup(gear + spare + candidate, speedGoal().apply { minEnhance = 12 })
        val r = HeroUpgrades().run(listOf(s), listOf(candidate))
        val pick = r.picks.single()
        val finishes = HeroUpgrades.finishes(listOf(candidate), HeroUpgrades.SIMS)
        assertEquals(HeroUpgrades.SIMS, pick.swaps.size)
        assertEquals(finishes.count { speedOf(it) > 10.0 }, pick.values.size)
        val base = r.bases.getValue(hero.id)
        assertEquals(5.0, base.value!! - base.worn!!, 0.01)

        // one roll left and at most 10 speed: never better than the spare, always better than the worn helmet
        val almost = piece(2, Slot.HELMET, 12, listOf(GearStat.SPD to 6.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val onlyWorn = HeroUpgrades().run(listOf(setup(gear + spare + almost, speedGoal().apply { minEnhance = 12 })), listOf(almost)).picks.single()
        assertTrue(onlyWorn.values.isEmpty()); assertEquals(HeroUpgrades.SIMS, onlyWorn.swaps.size)

        val tracked = HeroUpgrades(120).piece(listOf(s), candidate, java.util.concurrent.ConcurrentHashMap()).single()
        // as it is (7) it already beats the worn helmet: equipping it alone helps, and finishes must beat it in that build
        assertTrue(tracked.swapNow)
        val more = HeroUpgrades.finishes(listOf(candidate), 120)
        assertEquals(more.indices.filter { speedOf(more[it]) > 7.0 }.toSet(), tracked.swapHits.keys)
        assertTrue(tracked.swapBuild!!.filter { it.slot != Slot.HELMET }.all { it.equippedOn == hero.id })
    }

    // ---- one piece being enhanced ----

    @Test fun aTrackedPieceCountsTheFinishesThatBeatWhatTheHeroWearsAndNamesTheWeakestOnes() {
        val candidate = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val s = setup(worn() + candidate, speedGoal())
        val without = java.util.concurrent.ConcurrentHashMap<Long, HeroUpgrades.Base>()
        val r = HeroUpgrades(120).piece(listOf(s), candidate, without).single()
        val finishes = HeroUpgrades.finishes(listOf(candidate), 120)
        assertFalse(r.now)
        assertEquals(finishes.indices.filter { speedOf(finishes[it]) > 10.0 }.toSet(), r.hits.keys)
        assertEquals(120, r.total); assertEquals(0, r.unknown)
        assertTrue(without.containsKey(hero.id))
        // every finish that is an upgrade is at least as strong as one of the weakest, in every line
        fun atLeast(a: Item, b: Item) = a.subs.all { x -> x.value >= (b.subs.firstOrNull { it.type == x.type }?.value ?: 0.0) }
        val upgrades = r.hits.keys.map { finishes[it] }
        assertTrue(r.minimal.isNotEmpty() && r.minimal.all { m -> upgrades.any { it.subs.map { s -> s.value } == m.subs.map { s -> s.value } } })
        val all = HeroUpgrades.minimal(upgrades, n = 1000)
        assertTrue(upgrades.all { u -> all.any { m -> atLeast(u, m) } })
        assertTrue(all.none { m -> upgrades.any { u -> u !== m && atLeast(m, u) && !atLeast(u, m) } })
    }

    @Test fun aRecordedStepThatAlreadyBeatsTheWornPieceRaisesTheBarToItself() {
        val scanned = piece(1, Slot.HELMET, 0, listOf(GearStat.SPD to 9.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val s = setup(worn() + scanned, speedGoal())
        val recorded = piece(1, Slot.HELMET, 12, listOf(GearStat.SPD to 13.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val r = HeroUpgrades(120).piece(listOf(s), recorded, java.util.concurrent.ConcurrentHashMap()).single()
        assertTrue(r.now)
        val finishes = HeroUpgrades.finishes(listOf(recorded), 120)
        // one roll left: only finishes faster than the piece as it is now count
        assertEquals(finishes.indices.filter { speedOf(finishes[it]) > 13.0 }.toSet(), r.hits.keys)
        val finished = piece(1, Slot.HELMET, 15, listOf(GearStat.SPD to 13.0, GearStat.ATK_P to 4.0, GearStat.DEF_P to 4.0, GearStat.EFF to 4.0))
        val done = HeroUpgrades(120).piece(listOf(s), finished, java.util.concurrent.ConcurrentHashMap()).single()
        assertTrue(done.now); assertEquals(0, done.total); assertTrue(done.hits.isEmpty())
        assertTrue(done.changes, done.changes.startsWith("Spd +3"))
    }

    @Test fun aRecordedReforgeUsesTheGamesTablesAndItsGearWritesIntoTheBoxAndBack() {
        val finished = piece(1, Slot.HELMET, 15, listOf(GearStat.SPD to 14.0, GearStat.HP_P to 9.0, GearStat.CR to 4.0, GearStat.CD to 5.0))
        val rolls = listOf(4, 2, 1, 1)
        val withRolls = Item(finished.id, finished.slot, finished.set, finished.rank, 85, 15, finished.mainType, finished.mainValue,
            finished.subs.mapIndexed { i, s -> Substat(s.type, s.value, rolls[i], false) }, false, null, main15 = finished.mainValue)
        val r = UpgradeRecord(7, listOf(withRolls)).reforged()
        val q = r.item
        assertEquals(90, q.level); assertEquals(2835.0, q.mainValue, 0.0)
        // Lv85 reforge bonus by rolls: speed 4 rolls +3, hp% 2 rolls +3, crit 1 roll +1, crit damage 1 roll +1
        assertEquals(listOf(17.0, 12.0, 5.0, 6.0), q.subs.map { it.value })
        assertFalse(q.reforgeable)
        assertTrue(runCatching { UpgradeRecord(7, listOf(piece(2, Slot.HELMET, 12, finished.subs.map { it.type to it.value }))).reforged() }.isFailure)
        assertEquals(withRolls.subs.map { it.value }, r.undo().item.subs.map { it.value })

        val box = Box(JSONObject().put("items", JSONArray(listOf(withRolls).map(UpgradeRecord::itemJson))).put("heroes", JSONArray()))
        val before = box.writeStats(q)!!
        val written = Box(box.json).itemsById.getValue(q.id)
        assertEquals(90, written.level); assertEquals(q.subs.map { it.value }, written.subs.map { it.value }); assertEquals(2835.0, written.mainValue, 0.0)
        box.restore(q.id, before)
        assertEquals(withRolls.subs.map { it.value }, Box(box.json).itemsById.getValue(q.id).subs.map { it.value })
    }

    @Test fun aRecordedModChangesOnlyThatSubstatAndUndoes() {
        val p = piece(7, Slot.RING, 15, listOf(GearStat.DEF to 33.0, GearStat.SPD to 8.0, GearStat.CR to 9.0, GearStat.HP_P to 12.0))
        val box = Box(JSONObject().put("items", JSONArray(listOf(UpgradeRecord.itemJson(p)))).put("heroes", JSONArray()))
        val before = box.writeSubs(Mods.apply(p, Mod(0, GearStat.DEF, 33.0, GearStat.CD, 7.0)))!!
        val w = Box(box.json).itemsById.getValue(7)
        assertEquals(GearStat.CD to 7.0, w.subs[0].type to w.subs[0].value); assertTrue(w.subs[0].modified)
        assertEquals(p.subs.drop(1).map { it.type to it.value }, w.subs.drop(1).map { it.type to it.value })
        assertEquals(p.mainValue, w.mainValue, 0.0); assertEquals(p.level, w.level); assertEquals(p.enhance, w.enhance)
        box.restore(7, before)
        assertEquals(p.subs.map { it.type to it.value }, Box(box.json).itemsById.getValue(7).subs.map { it.type to it.value })
        // either stone, reforged or not: a substat that rolled once can become crit damage 3-8
        assertEquals(3..8, Mods.range(GearStat.CD, 1))
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
