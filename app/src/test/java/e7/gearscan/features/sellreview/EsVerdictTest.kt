package e7.gearscan.features.sellreview

import e7.gearscan.opt.EsForecast
import org.junit.Assert.assertEquals
import org.junit.Test

class EsVerdictTest {
    private fun es(now: Int, chance: Double, finishShare: Double = .5, possible: Boolean = true, supported: Boolean = true, bar: Int? = 90) =
        EsForecast.Es(now, bar, chance, null, null, possible, supported, finishShare)

    @Test fun aPieceWorthRollingOnIsKeptNotSold() {
        // the owner's Penetration weapon at +9: 11% to reach 90, with the 1% default cutoff
        assertEquals(true, esKeeps(es(53, .11), rollOn = 1.0))
        assertEquals(false, esKeeps(es(53, .11), rollOn = 20.0))
    }

    @Test fun barReachedKeepsAndNoWayUpSells() {
        assertEquals(true, esKeeps(es(91, 1.0), rollOn = 50.0))
        assertEquals(false, esKeeps(es(60, 0.0, finishShare = 0.0), rollOn = 1.0))
        assertEquals(false, esKeeps(es(60, 0.0, possible = true), rollOn = 0.0))
    }

    @Test fun noForecastNoVerdict() {
        assertEquals(null, esKeeps(es(60, .5, supported = false), rollOn = 1.0))
        assertEquals(null, esKeeps(es(60, .5, bar = null), rollOn = 1.0))
    }
}
