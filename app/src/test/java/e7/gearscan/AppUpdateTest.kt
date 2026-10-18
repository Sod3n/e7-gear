package e7.gearscan

import e7.gearscan.data.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    private fun release(tag: String, pre: Boolean = false, assets: String = "[]") =
        """{"tag_name":"$tag","html_url":"https://github.com/Sod3n/e7-gear/releases/tag/$tag","draft":false,"prerelease":$pre,"assets":$assets}"""

    @Test fun offersANewerRelease() = assertEquals("1.0.1", AppUpdate.newer("1.0.0", release("v1.0.1"))?.version)
    @Test fun countsVersionsAsNumbers() = assertEquals("1.10.0", AppUpdate.newer("1.9.3", release("v1.10.0"))?.version)
    @Test fun ignoresTheSameVersion() = assertNull(AppUpdate.newer("1.0.0", release("v1.0.0")))
    @Test fun ignoresAnOlderRelease() = assertNull(AppUpdate.newer("1.2.0", release("v1.1.9")))
    @Test fun findsTheApk() {
        val r = AppUpdate.newer("1.0.0", release("v1.0.1", assets = """[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
            {"name":"E7-Gear-1.0.1.apk","browser_download_url":"https://x/E7-Gear-1.0.1.apk","size":123}]"""))!!
        assertEquals("https://x/E7-Gear-1.0.1.apk" to 123L, r.apk to r.size)
    }
    @Test fun withoutAnApkOnlyThePageHelps() = assertNull(AppUpdate.newer("1.0.0", release("v1.0.1"))!!.apk)
    @Test fun ignoresPrereleases() = assertNull(AppUpdate.newer("1.0.0", release("v2.0.0", pre = true)))
}
