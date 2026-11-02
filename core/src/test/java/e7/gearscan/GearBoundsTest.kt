package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import org.junit.Assert.*
import org.junit.Test

class GearBoundsTest {
    private fun piece(rank: String = "Epic") =
        Item(
            1,
            Slot.HELMET,
            "SpeedSet",
            rank,
            85,
            9,
            GearStat.HP,
            1512.0,
            listOf(Substat(GearStat.SPD, 14.0, 4, false), Substat(GearStat.HP_P, 5.0, 1, false), Substat(GearStat.DEF_P, 4.0, 1, false)) +
                if (rank == "Epic") listOf(Substat(GearStat.CR, 4.0, 1, false)) else emptyList(),
            false,
            null,
            main15 = 2700.0,
        )

    @Test fun finalTargetsUseRareEndpointsAndReforgeOnlyWhenApplicable() {
        assertEquals(34.0, GearBounds.target(GearStat.SPD, true).maximum, 0.0)
        assertEquals(30.0, GearBounds.target(GearStat.SPD, false).maximum, 0.0)
        assertEquals(34.0, GearBounds.target(GearStat.SPD, false, includeFinished = true).maximum, 0.0)
        assertEquals(56.0, GearBounds.target(GearStat.HP_P, true).maximum, 0.0)
        assertEquals(36.0, GearBounds.target(GearStat.CR, true).maximum, 0.0)
        assertEquals(49.0, GearBounds.target(GearStat.CD, true).maximum, 0.0)
        assertEquals(1548.0, GearBounds.target(GearStat.HP, true).maximum, 0.0)
        assertEquals(264.0, GearBounds.target(GearStat.DEF, true).maximum, 0.0)
    }

    @Test fun nextRollBoundsUseTheCurrentValueAndRevealsLeaveOtherLinesFixed() {
        assertEquals(GearBounds.Limits(16.0, 19.0), GearBounds.existingLine(piece(), 0, 12, false))
        assertEquals(GearBounds.Limits(14.0, 14.0), GearBounds.existingLine(piece("Heroic"), 0, 12, false))
        assertEquals(GearBounds.Limits(4.0, 8.0), GearBounds.newLine(GearStat.EFF, 85))
        assertEquals(GearBounds.Limits(3.0, 5.0), GearBounds.newLine(GearStat.SPD, 88))
    }

    @Test fun invalidRollsAndWrongMainTotalsCannotBeSaved() {
        val p = piece()
        val r = UpgradeRecord(1, listOf(p))
        val values = p.subs.map { it.value }
        assertTrue(runCatching { r.record(12, 1944.0, values.toMutableList().also { it[0] = 15.0 }) }.isFailure)
        assertTrue(runCatching { r.record(12, 1944.0, values.toMutableList().also { it[0] = 20.0 }) }.isFailure)
        assertTrue(runCatching { r.record(12, 2106.0, values.toMutableList().also { it[0] = 18.0 }) }.isFailure)
        assertEquals(
            19.0,
            r
                .record(12, 1944.0, values.toMutableList().also { it[0] = 19.0 })
                .item.subs[0]
                .value,
            0.0,
        )
        val h = piece("Heroic")
        val reveal = UpgradeRecord(1, listOf(h))
        assertTrue(runCatching { reveal.record(12, 1944.0, h.subs.map { it.value }, GearStat.EFF to 9.0) }.isFailure)
    }

    @Test fun correctionMainComesFromTheOriginalScanRatherThanAMistypedEntry() {
        val original = piece()
        val wrong =
            Item(1, original.slot, original.set, original.rank, 85, 12, GearStat.HP, 2106.0, original.subs, false, null, main15 = 2700.0)
        assertEquals(GearBounds.Limits(1944.0, 1944.0), GearBounds.main(UpgradeRecord(1, listOf(original, wrong)), 12))
    }

    @Test fun impossibleTargetsAreRejectedBeforeSearching() {
        assertNotNull(PotentialQuery(targets = mapOf(GearStat.SPD to "35")).error())
        assertNull(PotentialQuery(targets = mapOf(GearStat.SPD to "34")).error())
        assertNotNull(PotentialQuery(targets = mapOf(GearStat.SPD to "34"), reforge = false).error())
        assertNull(PotentialQuery(targets = mapOf(GearStat.SPD to "34"), reforge = false, show = 2).error())
    }
}
