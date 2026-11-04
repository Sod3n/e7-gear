package e7.gearscan.features.settings

import e7.gearscan.AppGraph
import e7.gearscan.state.Disk
import e7.gearscan.state.NoPlatform
import e7.gearscan.state.Platform
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFeatureTest {

    private class Phone : Platform by NoPlatform {
        var overlayStops = 0

        override fun stopOverlay(onlyWhenTracking: Boolean) { overlayStops++ }
    }

    private fun graph(scope: CoroutineScope, phone: Phone) = AppGraph(Disk(createTempDirectory("settings").toFile()), scope, platform = phone)

    @Test fun theProxyAndTheOverlayIntroSurviveEraseButConsentDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val g = graph(this, Phone())
        g.settings.consent = true
        g.settings.proxyOn = true
        g.settings.proxy = "127.0.0.1:8080"
        g.settings.overlaySeen = true
        g.store.erase()
        assertFalse(g.settings.consent)
        assertTrue(g.settings.proxyOn)
        assertEquals("127.0.0.1:8080", g.settings.proxy)
        assertTrue(g.settings.overlaySeen)
    }
}
