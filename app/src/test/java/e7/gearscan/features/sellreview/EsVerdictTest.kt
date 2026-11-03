package e7.gearscan.features.sellreview

import e7.gearscan.opt.EsForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsVerdictTest {
    private fun es(now: Int, chance: Double, best: Double?, finishShare: Double = .5, possible: Boolean = true, supported: Boolean = true, bar: Int? = 90) =
        EsForecast.Es(now, bar, chance, null, best, possible, supported, finishShare)

    @Test fun aPieceWhoseUsualRollsReachTheBarIsKeptWhateverItsChance() {
        // the owner's helmet: 75-92 against a bar of 90, a small chance below the worth-rolling cutoff
        val helmet = es(47, .004, best = 92.0)
        assertEquals(true, esKeeps(helmet))
        assertFalse(esRollsOn(helmet, rollOn = 1.0))
        assertTrue(esRollsOn(es(53, .11, best = 96.0), rollOn = 1.0))
    }

    @Test fun onlyPiecesThatCannotReachTheBarAreSells() {
        assertEquals(false, esKeeps(es(47, 0.0, best = 87.0)))
        assertEquals(false, esKeeps(es(60, 0.0, best = 60.0, finishShare = 0.0)))
        assertEquals(true, esKeeps(es(91, 1.0, best = 91.0)))
        assertEquals(true, esKeeps(es(85, 1.0, best = 93.0, finishShare = 0.0)))
    }

    @Test fun noForecastNoVerdict() {
        assertEquals(null, esKeeps(es(60, .5, best = 95.0, supported = false)))
        assertEquals(null, esKeeps(es(60, .5, best = 95.0, bar = null)))
    }
}
