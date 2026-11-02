package e7.gearscan

import e7.gearscan.data.Http
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGuardTest {
    @Test fun fribbelsIsBlockedUnderTestsOnly() {
        val open = Http("E7Gear")
        val blocked = Http("E7Gear", blockThirdParty = true)
        assertTrue(open.thirdParty(Fribbels.API))
        assertTrue(open.thirdParty("https://krivpfvxi0.execute-api.us-west-2.amazonaws.com/dev/getBuilds"))
        assertFalse(open.thirdParty("https://e7-gear-content.pages.dev/content.json"))
        assertTrue(runCatching { blocked.open(Fribbels.API) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { blocked.open("https://e7-gear-content.pages.dev/content.json") }.isSuccess)
    }
}
