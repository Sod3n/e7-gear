package e7.gearscan

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import e7.gearscan.data.AppUpdate
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Self-update end to end: the app takes update-test.apk (a newer build of itself, pushed to its files folder) through
 * download, checks and Android's installer. Whoever runs it confirms the prompt; the new version then replaces this process.
 */
class SelfUpdateTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun updatesItself() {
        val apk = File(rule.activity.getExternalFilesDir(null), "update-test.apk")
        assumeTrue("needs a newer build at ${apk.path}", apk.exists())
        val model = ViewModelProvider(rule.activity)[AppModel::class.java]
        rule.waitUntil(20_000) { model.ready }
        rule.runOnUiThread {
            model.appUpdate = AppUpdate.Release("1.0.2", "https://example.invalid", apk.toURI().toString(), apk.length())
            model.updateApp()
        }
        rule.waitUntil(60_000) { model.updateProgress == null }
        assertNull(model.updateProblem?.takeUnless { it.startsWith("Allow installs") })
        // keeps the test clock drawing frames while the installer is up; the new version ends this process
        runCatching { rule.waitUntil(180_000) { false } }
    }
}
