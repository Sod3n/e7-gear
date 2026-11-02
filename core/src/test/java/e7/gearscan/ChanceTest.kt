package e7.gearscan

import e7.gearscan.value.Chance
import org.junit.Assert.assertEquals
import org.junit.Test

class ChanceTest {
    private fun t(p: Double) = Chance(p).text

    @Test
    fun twoSignificantDigits() {
        assertEquals("25%", t(.25))
        assertEquals("13%", t(.125))
        assertEquals("8.3%", t(1.0 / 12))
        assertEquals("5%", t(.05))
        assertEquals("0.42%", t(.0042))
        assertEquals("0.05%", t(.0005))
        assertEquals("10%", t(.0996))
    }

    @Test
    fun onlyCertainChancesReadNoneOrAll() {
        assertEquals("0%", t(0.0))
        assertEquals("0%", t(Double.NaN))
        assertEquals("100%", t(1.0))
        assertEquals("99%", t(.996))
        assertEquals("<0.01%", t(.000001))
        assertEquals("0.01%", t(.0001))
    }
}
