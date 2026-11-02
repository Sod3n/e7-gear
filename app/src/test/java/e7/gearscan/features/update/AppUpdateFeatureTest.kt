package e7.gearscan.features.update

import e7.gearscan.AppGraph
import e7.gearscan.data.AppUpdate
import e7.gearscan.data.Http
import e7.gearscan.state.Disk
import e7.gearscan.state.NoPlatform
import e7.gearscan.state.Platform
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateFeatureTest {

    private class Phone(
        val dir: File,
        var refuse: String? = null,
        var downloadFails: Throwable? = null,
        override var canInstall: Boolean = true,
    ) : Platform by NoPlatform {
        val pages = mutableListOf<String>()
        val installed = mutableListOf<File>()
        var asked = 0

        override val appVersion = "1.0.0"

        override fun openPage(url: String) { pages += url }

        override fun updateFile(version: String) = File(dir, "E7-Gear-$version.apk")

        override fun download(http: Http, url: String, to: File, size: Long, progress: (Float) -> Unit) {
            downloadFails?.let { throw it }
            progress(0.5f)
            to.writeText("apk")
        }

        override fun refusal(apk: File) = refuse

        override fun install(apk: File) { installed += apk }

        override fun allowInstalls() { asked++ }
    }

    private fun graph(scope: CoroutineScope, phone: Phone) =
        AppGraph(Disk(createTempDirectory("update").toFile()), scope, platform = phone)

    private val withApk = AppUpdate.Release("1.0.1", "https://example.invalid/page", "https://example.invalid/a.apk", 3)

    @Test fun aReleaseWithoutAnApkOpensItsPage() = runTest(UnconfinedTestDispatcher()) {
        val phone = Phone(createTempDirectory("apk").toFile())
        val g = graph(this, phone)
        g.appUpdate.release = AppUpdate.Release("1.0.1", "https://example.invalid/page")
        g.appUpdate.update()
        assertEquals(listOf("https://example.invalid/page"), phone.pages)
        assertNull(g.appUpdate.progress)
    }

    @Test fun aCheckedDownloadGoesToTheInstaller() = runTest(UnconfinedTestDispatcher()) {
        val phone = Phone(createTempDirectory("apk").toFile())
        val g = graph(this, phone)
        g.appUpdate.release = withApk
        g.appUpdate.update()
        awaitUntil { phone.installed.size == 1 }
        assertEquals(1, phone.installed.size)
        assertNull(g.appUpdate.problem)
    }

    @Test fun aStoppedDownloadAsksToTryAgainAndAnUnsignedOneIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val phone = Phone(createTempDirectory("apk").toFile(), downloadFails = IOException("reset"))
        val g = graph(this, phone)
        g.appUpdate.release = withApk
        g.appUpdate.update()
        awaitUntil { g.appUpdate.problem == "Download stopped. Tap to try again." }
        assertEquals("Download stopped. Tap to try again.", g.appUpdate.problem)
        assertTrue(phone.installed.isEmpty())

        phone.downloadFails = null
        phone.refuse = "it isn't signed with this app's key"
        g.appUpdate.update()
        awaitUntil { g.appUpdate.problem == "Not installed: it isn't signed with this app's key." }
        assertEquals("Not installed: it isn't signed with this app's key.", g.appUpdate.problem)
        assertFalse(phone.updateFile("1.0.1").exists())
    }

    @Test fun withoutPermissionItWaitsAndInstallsOnceAllowed() = runTest(UnconfinedTestDispatcher()) {
        val phone = Phone(createTempDirectory("apk").toFile(), canInstall = false)
        val g = graph(this, phone)
        g.appUpdate.release = withApk
        g.appUpdate.update()
        awaitUntil { g.appUpdate.problem == "Allow installs from E7 Gear, then come back." }
        assertEquals("Allow installs from E7 Gear, then come back.", g.appUpdate.problem)
        assertEquals(1, phone.asked)
        g.appUpdate.resumed()
        assertTrue(phone.installed.isEmpty())
        phone.canInstall = true
        g.appUpdate.resumed()
        assertEquals(1, phone.installed.size)
        assertNull(g.appUpdate.problem)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!condition()) delay(5) }
    }
}
