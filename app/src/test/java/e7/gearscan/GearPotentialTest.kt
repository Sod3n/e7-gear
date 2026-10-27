package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.GearPotential
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GearPotentialTest {
    private fun item(enhance: Int = 12, speed: Double = 13.0, rolls: Int = 4, rank: String = "Epic", level: Int = 85,
                     subs: List<Substat>? = null, slot: Slot = Slot.HELMET, main: GearStat = GearStat.HP) =
        Item(1, slot, "SpeedSet", rank, level, enhance, main, 2700.0,
            subs ?: (listOf(Substat(GearStat.SPD, speed, rolls, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.CR, 5.0, 1, false)) +
                if (rank == "Epic" || enhance >= 12) listOf(Substat(GearStat.CD, 7.0, 1, false)) else emptyList()), false, null, main15 = 2700.0)
    private fun chance(item: Item, vararg targets: Pair<GearStat, Double>, reforge: Boolean = true, allowed: Boolean = true) =
        GearPotential.forecast(item, GearPotential.Goal(targets.toMap(), reforge, allowed))

    @Test fun matchesTheInventorySpeedCases() {
        assertEquals(.25, chance(item(9, 14.0, rank = "Heroic"), GearStat.SPD to 20.0).chance, 1e-12)
        assertEquals(1.0 / 6, chance(item(), GearStat.SPD to 20.0).chance, 1e-12)
        assertEquals(1.0 / 12, chance(item(9, 12.0, rank = "Heroic"), GearStat.SPD to 20.0).chance, 1e-12)
        assertEquals(1.0, chance(item(15, 16.0, 5), GearStat.SPD to 20.0).chance, 0.0)
        assertEquals(0.0, chance(item(15, 16.0, 5), GearStat.SPD to 20.0, reforge = false).chance, 0.0)
        assertEquals(0.0, chance(item(15, 16.0, 5), GearStat.SPD to 20.0, allowed = false).chance, 0.0)
    }
    @Test fun twoIndividuallyPossibleTargetsCanBeJointlyImpossible() {
        val q = item(12, 16.0)
        assertTrue(chance(q, GearStat.SPD to 20.0, reforge = false).chance > 0)
        assertTrue(chance(q, GearStat.CR to 10.0, reforge = false).chance > 0)
        val both = chance(q, GearStat.SPD to 20.0, GearStat.CR to 10.0, reforge = false)
        assertEquals(0.0, both.chance, 0.0); assertFalse(both.possible)
    }
    @Test fun jointOddsCountBothPossibleRollOrders() {
        val both = chance(item(9, 12.0), GearStat.SPD to 16.0, GearStat.CR to 10.0, reforge = false)
        assertEquals(1.0 / 72, both.chance, 1e-12)
    }
    @Test fun heroicNewStatSelectionAndThenAnUpgradeAreBothCounted() {
        val q = item(9, rank = "Heroic", subs = listOf(Substat(GearStat.CR, 5.0, 1, false), Substat(GearStat.CD, 7.0, 1, false), Substat(GearStat.HP_P, 5.0, 1, false)))
        val pool = UpgradeRecord.allowedNew(q).size
        assertEquals(1.0 / pool / 4 * (6.0 / 9), chance(q, GearStat.SPD to 6.0, reforge = false).chance, 1e-12)
        assertFalse(chance(q, GearStat.SPD to 6.0, GearStat.EFF to 5.0).possible)
    }
    @Test fun mainStatsAndIllegalSlotStatsDoNotCountAsSubstats() {
        val q = item(9, rank = "Heroic", slot = Slot.WEAPON, main = GearStat.ATK)
        assertFalse(chance(q, GearStat.ATK to 300.0).possible)
        assertFalse(chance(q, GearStat.DEF_P to 5.0).possible)
    }
    @Test fun rareEndpointsArePossibleButDoNotGetInventedPercentages() {
        val q = item(12, 15.0, level = 88)
        val out = chance(q, GearStat.SPD to 20.0)
        assertEquals(0.0, out.chance, 0.0); assertTrue(out.possible); assertTrue(out.rareOnly)
        assertFalse(chance(q, GearStat.SPD to 21.0).possible)
    }
    @Test fun level88NeverReceivesReforgeGainsAndFinished90IsNotReforgedTwice() {
        assertEquals(chance(item(12, 15.0, level = 88), GearStat.SPD to 19.0, reforge = false), chance(item(12, 15.0, level = 88), GearStat.SPD to 19.0))
        assertFalse(chance(item(15, 19.0, level = 90), GearStat.SPD to 20.0).possible)
        assertTrue(chance(item(15, 20.0, level = 90), GearStat.SPD to 20.0).matchesNow)
    }
    @Test fun uniqueGearDoesNotGetAReforgeBonusEvenWhenItsNameWasNotDecoded() {
        val raw = UpgradeRecord.itemJson(item(15, 16.0, 5)).put("name", "Unknown").put("mainStatId", "un6_helm_m1")
        val b = Box(JSONObject().put("items", JSONArray().put(raw)).put("heroes", JSONArray()))
        assertFalse(b.canReforge(1))
        raw.put("mainStatId", "cra6_helm_m1")
        assertTrue(Box(JSONObject().put("items", JSONArray().put(raw))).canReforge(1))
    }
    @Test fun queryRoundTripsAndCombinesSlotSetMainAndChanceFilters() {
        val q = PotentialQuery(sets = setOf("SpeedSet", "HitSet"), mains = setOf(GearStat.HP), minimumChance = "10")
        assertEquals(q, PotentialQuery.of(JSONObject(q.toJson().toString())))
        assertNull(q.error()); assertTrue(q.includes(item()))
        assertFalse(q.includes(item(slot = Slot.BOOTS)))
        assertFalse(q.copy(sets = setOf("HealthSet")).includes(item()))
        assertFalse(q.copy(mains = setOf(GearStat.SPD)).includes(item()))
        assertFalse(q.accepts(GearPotential.Outcome(.05, true, true, false)))
        assertTrue(q.accepts(GearPotential.Outcome(.25, true, true, false)))
        assertNotNull(q.copy(slots = emptySet()).error())
        assertNotNull(q.copy(targets = mapOf(GearStat.SPD to "")).error())
        assertNotNull(q.copy(minimumChance = "101").error())
    }
    @Test fun gearWithoutMainHistoryStillHasAValidSubstatForecast() {
        val old = item()
        val q = Item(old.id, old.slot, old.set, old.rank, old.level, old.enhance, old.mainType, old.mainValue, old.subs, false, null)
        assertEquals(1.0 / 6, chance(q, GearStat.SPD to 20.0).chance, 1e-12)
    }
    @Test fun alreadyMeetingMinimumsNeedsNoForecastEvenOnOlderGear() {
        val out = chance(item(6, 12.0, level = 75), GearStat.SPD to 10.0)
        assertTrue(out.matchesNow); assertTrue(out.supported); assertEquals(1.0, out.chance, 0.0)
    }
    @Test fun tinyOddsStayVisibleAndProgressCannotReduceMinimumOnlyOdds() {
        assertEquals("<0.01%", GearPotential.percent(.000001))
        assertTrue(chance(item(0, 3.0, 1), GearStat.SPD to 20.0).chance > 0)
        assertEquals(1.0, chance(item(3, 20.0, 2), GearStat.SPD to 20.0).chance, 1e-12)
    }
}
