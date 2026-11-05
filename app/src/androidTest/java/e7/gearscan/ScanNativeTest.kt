package e7.gearscan

import org.junit.Test

/** Loads and registers JNI like scan startup, without starting a VPN or contacting the game. */
class ScanNativeTest {
    @Test
    fun scanServiceLoadsNativeLibrary() {
        Class.forName("e7.gearscan.TProxyService")
    }
}
