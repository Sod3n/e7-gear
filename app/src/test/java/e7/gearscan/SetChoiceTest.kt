package e7.gearscan

import e7.gearscan.data.Content
import e7.gearscan.data.SetChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SetChoiceTest {
    private val content = TestContent.content

    @Test
    fun fourOrFourPlusAnyOfTwo() {
        val alts = SetChoice(listOf("SpeedSet", "RevengeSet"), mapOf("CriticalSet" to 1, "ImmunitySet" to 1)).toAlts()
        assertEquals(setOf(mapOf("SpeedSet" to 4, "CriticalSet" to 2), mapOf("RevengeSet" to 4, "ImmunitySet" to 2),
            mapOf("SpeedSet" to 4, "ImmunitySet" to 2), mapOf("RevengeSet" to 4, "CriticalSet" to 2)), alts.toSet())
    }

    @Test
    fun tappingATwoPieceSetThreeTimesAsksForThree() {
        var c = SetChoice(emptyList(), emptyMap())
        repeat(3) { c = c.tapTwo("HealthSet") }
        assertEquals(listOf(mapOf("HealthSet" to 6)), c.toAlts())
        assertEquals(emptyList<Map<String, Int>>(), c.tapTwo("HealthSet").toAlts())
        // counts add up and never pass three pairs
        val mixed = SetChoice(emptyList(), emptyMap()).tapTwo("HealthSet").tapTwo("HealthSet").tapTwo("CriticalSet")
        assertEquals(listOf(mapOf("HealthSet" to 4, "CriticalSet" to 2)), mixed.toAlts())
        assertEquals(mixed.two, mixed.tapTwo("HitSet").two)
    }

    @Test
    fun aSecondTapBesideAFourPieceSetMakesRoom() {
        val c = SetChoice(listOf("SpeedSet"), emptyMap()).tapTwo("HealthSet")
        assertEquals(listOf(mapOf("SpeedSet" to 4, "HealthSet" to 2)), c.toAlts())
        assertEquals(listOf(mapOf("HealthSet" to 4)), c.tapTwo("HealthSet").toAlts())
    }

    @Test
    fun presetsRoundTrip() {
        for (alts in listOf(listOf(mapOf("SpeedSet" to 4, "CriticalSet" to 2)), listOf(mapOf("HealthSet" to 6)),
            listOf(mapOf("HealthSet" to 4, "CriticalSet" to 2)))) {
            val c = SetChoice.from(alts, content)
            assertNull(c.mix); assertEquals(alts, c.toAlts())
        }
        assertEquals(emptyList<Map<String, Int>>(), SetChoice.from(emptyList(), content).toAlts())
    }

    @Test
    fun aRoleMixIsKeptUntilEdited() {
        val role = SetChoice.anyPairs(listOf("HealthSet", "DefenseSet"), 2)
        assertEquals(setOf(mapOf("HealthSet" to 4), mapOf("HealthSet" to 2, "DefenseSet" to 2), mapOf("DefenseSet" to 4)), role.toAlts().toSet())
        val back = SetChoice.from(role.toAlts(), content)
        assertEquals(role.toAlts().toSet(), back.toAlts().toSet())
        assertEquals(listOf(mapOf("HitSet" to 2)), back.tapTwo("HitSet").toAlts())
    }
}
