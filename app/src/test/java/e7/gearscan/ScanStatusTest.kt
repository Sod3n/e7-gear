package e7.gearscan

import e7.gearscan.data.ScanStatus
import e7.gearscan.data.ScanStep
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStatusTest {
    private val kb = 1024L

    @Test fun walksThroughALogin() {
        assertEquals(ScanStep.WAITING, ScanStatus.of(0, 0, 1_000))
        assertEquals(ScanStep.CONNECTED, ScanStatus.of(2, 3 * kb, 5_000))
        assertEquals(ScanStep.LOGIN, ScanStatus.of(4, 40 * kb, 12_000))
        assertEquals(ScanStep.LOBBY, ScanStatus.of(7, 363 * kb, 20_000))
    }

    @Test fun noLoginAfterHalfAMinuteIsLate() {
        assertEquals(ScanStep.LATE, ScanStatus.of(0, 0, 30_000))
        // an already-logged-in game still talks a little, but never sends the login burst
        assertEquals(ScanStep.LATE, ScanStatus.of(3, 6 * kb, 45_000))
    }

    @Test fun progressWinsOverTheClock() {
        assertEquals(ScanStep.LOGIN, ScanStatus.of(3, 9 * kb, 90_000))
        assertEquals(ScanStep.LOBBY, ScanStatus.of(5, 101 * kb, 90_000))
    }

    @Test fun lobbyMatchesTheNotificationThreshold() {
        assertEquals(TProxyService.LOBBY_KB * 1024, ScanStatus.LOBBY_BYTES)
    }
}
