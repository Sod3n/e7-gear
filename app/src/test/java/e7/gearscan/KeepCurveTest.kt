package e7.gearscan

import e7.gearscan.data.*
import e7.gearscan.opt.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class KeepCurveTest {
    /** Against the offline stop-rule sweep (gear_cleanup/stop_rules/sweep_p50.jsonl) on the same box, bars and no reforge. */
    @Test fun matchesTheOfflineSweep() {
        val path = System.getenv("E7_GEAR"); assumeTrue(path != null && File(path).exists())
        val box = Box.parse(File(path!!).readText())
        val bag = box.items.filter { it.equippedOn == null && it.enhance < 15 }
        val bars = mapOf(Slot.WEAPON to 88, Slot.ARMOR to 85, Slot.NECKLACE to 87, Slot.HELMET to 85, Slot.RING to 88, Slot.BOOTS to 87)
        val r = KeepCurve.run(bag, bars, { false })
        val warm = KeepCurve.run(bag, bars, { false }); val one = KeepCurve.run(bag, bars, { false }, threads = 1)
        println("warm ${warm.millis} ms on all cores, ${one.millis} ms on one core")
        val full = 156.25 * 350 * 1.5 * EsForecast.GOLD_PER_XP
        val at = { t: Double -> KeepCurve.THRESHOLDS.indexOfFirst { kotlin.math.abs(it - t) < 1e-12 } }
        println("pieces ${r.pieces} in ${r.millis} ms; possible ${r.possible}; 1%: ${r.good[at(.01)]} good, ${r.gold[at(.01)] / full} enhances; 10%: ${r.good[at(.1)]}, ${r.gold[at(.1)] / full}")
        assertEquals(84.34, r.good[at(.01)], 1.0)
        assertEquals(182.5, r.gold[at(.01)] / full, 182.5 * .03)
        assertEquals(73.69, r.good[at(.1)], 1.5)
        assertEquals(112.8, r.gold[at(.1)] / full, 112.8 * .04)
    }

    @Test fun strictnessOnlyEverSavesGold() {
        val path = System.getenv("E7_GEAR"); assumeTrue(path != null && File(path).exists())
        val bag = Box.parse(File(path!!).readText()).items.filter { it.equippedOn == null && it.enhance < 15 }.take(200)
        val r = KeepCurve.run(bag, Slot.entries.associateWith { 88 }, { true })
        for (j in 1 until KeepCurve.THRESHOLDS.size) { assertTrue(r.gold[j] <= r.gold[j - 1] + 1e-6); assertTrue(r.good[j] <= r.good[j - 1] + 1e-9) }
        assertTrue(r.good[0] <= r.possible + 1e-9)
    }
}
