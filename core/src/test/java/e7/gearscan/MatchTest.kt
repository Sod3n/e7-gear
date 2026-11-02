package e7.gearscan

import e7.gearscan.data.Match
import e7.gearscan.data.SetDef
import e7.gearscan.data.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchTest {
    private fun t(vararg p: Pair<Stat, Double>) = DoubleArray(8) { Double.NaN }.also { a -> p.forEach { a[it.first.ordinal] = it.second } }
    private fun w(vararg p: Pair<Stat, Int>) = IntArray(8).also { a -> p.forEach { a[it.first.ordinal] = it.second } }

    @Test
    fun progressFromFloorToTarget() {
        val base = mapOf(Stat.SPD to 100.0, Stat.CD to 150.0)
        val now = mapOf(Stat.SPD to 200.0, Stat.CD to 225.0)
        val m = Match.of({ now.getValue(it) }, { base.getValue(it) }, t(Stat.SPD to 200.0, Stat.CD to 300.0), w(Stat.SPD to 3, Stat.CD to 1), "x")!!
        // shortfall now 75 (crit damage, weight 1) of 450 at the floor (speed 3×100 + crit damage 150)
        assertEquals(83, m.pct)
        assertEquals(Stat.SPD, m.rows.first().stat)
    }

    @Test
    fun overshootCountsAsDoneAndNoTargetsGivesNothing() {
        val m = Match.of({ 120.0 }, { 0.0 }, t(Stat.CR to 100.0), w(Stat.CR to 2), "x")!!
        assertEquals(100, m.pct)
        assertNull(Match.of({ 0.0 }, { 0.0 }, t(), w(), "x"))
    }

    private fun set(key: String, pieces: Int, spd: Double = 0.0) = SetDef(key, key, key, pieces, "", 0.0, 0.0, 0.0, spd, 0.0, 0.0, 0.0, 0.0, 0.0, false)

    @Test
    fun missingEffectSetCostsAShareButStatSetsDoNot() {
        val sets = listOf(set("vampire", 4), set("speed", 4, 25.0), set("immune", 2)).associateBy { it.key }
        val alts = listOf(mapOf("vampire" to 4, "immune" to 2))
        // stats can't show lifesteal or immunity, so both count; a missing speed set already shows in speed
        assertEquals(listOf("vampire", "immune"), Match.missingSets(mapOf("speed" to 4, "immune" to 1), alts, sets::get).map { it.key })
        assertEquals(emptyList<SetDef>(), Match.missingSets(mapOf("vampire" to 4, "immune" to 2), alts, sets::get))
        assertEquals(emptyList<SetDef>(), Match.missingSets(emptyMap(), listOf(mapOf("speed" to 4)), sets::get))
        // the closest of several choices decides
        assertEquals(listOf("immune"), Match.missingSets(mapOf("speed" to 4), listOf(mapOf("vampire" to 4), mapOf("speed" to 4, "immune" to 2)), sets::get).map { it.key })
        val m = Match.of({ 120.0 }, { 0.0 }, t(Stat.CR to 100.0), w(Stat.CR to 2), "x", missing = listOf(sets.getValue("vampire")))!!
        assertEquals(70, m.pct)
    }

    @Test
    fun rageAndPenetrationCountAsEffectSets() {
        // their bonuses (conditional damage, defense ignore) never show in the eight stats
        val rage = SetDef("rage", "rage", "rage", 4, "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 30.0, false)
        val pen = SetDef("pen", "pen", "pen", 2, "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true)
        val sets = listOf(rage, pen).associateBy { it.key }
        assertEquals(listOf("rage", "pen"), Match.missingSets(emptyMap(), listOf(mapOf("rage" to 4, "pen" to 2)), sets::get).map { it.key })
    }
}
