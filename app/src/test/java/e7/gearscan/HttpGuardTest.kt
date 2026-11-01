package e7.gearscan

import e7.gearscan.data.Http
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGuardTest {
    @After fun reset() { Http.blockThirdParty = false }

    @Test fun fribbelsIsBlockedUnderTestsOnly() {
        assertTrue(Http.thirdParty(Fribbels.API))
        assertTrue(Http.thirdParty("https://krivpfvxi0.execute-api.us-west-2.amazonaws.com/dev/getBuilds"))
        assertFalse(Http.thirdParty("https://e7-gear-content.pages.dev/content.json"))
        Http.blockThirdParty = true
        assertTrue(runCatching { Http.open(Fribbels.API) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { Http.open("https://e7-gear-content.pages.dev/content.json") }.isSuccess)
    }
}
