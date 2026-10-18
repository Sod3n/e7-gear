package e7.gearscan

import e7.gearscan.data.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    private fun release(tag: String, pre: Boolean = false) =
        """{"tag_name":"$tag","html_url":"https://github.com/Sod3n/e7-gear/releases/tag/$tag","draft":false,"prerelease":$pre}"""

    @Test fun offersANewerRelease() = assertEquals("1.0.1", AppUpdate.newer("1.0.0", release("v1.0.1"))?.version)
    @Test fun countsVersionsAsNumbers() = assertEquals("1.10.0", AppUpdate.newer("1.9.3", release("v1.10.0"))?.version)
    @Test fun ignoresTheSameVersion() = assertNull(AppUpdate.newer("1.0.0", release("v1.0.0")))
    @Test fun ignoresAnOlderRelease() = assertNull(AppUpdate.newer("1.2.0", release("v1.1.9")))
    @Test fun ignoresPrereleases() = assertNull(AppUpdate.newer("1.0.0", release("v2.0.0", pre = true)))
}
