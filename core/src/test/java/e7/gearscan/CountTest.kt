package e7.gearscan

import e7.gearscan.value.Count
import org.junit.Assert.assertEquals
import org.junit.Test

class CountTest {
    @Test fun oneTakesTheSingularEverythingElseThePlural() {
        assertEquals("0 pieces", Count(0).of("piece"))
        assertEquals("1 piece", Count(1).of("piece"))
        assertEquals("2 pieces", Count(2).of("piece"))
        assertEquals("1 match", Count(1).of("match", "matches"))
        assertEquals("12 matches", Count(12).of("match", "matches"))
    }
}
