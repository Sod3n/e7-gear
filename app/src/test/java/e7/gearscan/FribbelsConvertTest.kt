package e7.gearscan

import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Converts a saved /getItems response for comparison with tools/gear_scan/decode_scan.py output:
 * E7_RAW=raw.json E7_OUT=out.json ./gradlew testDebugUnitTest. Skipped when E7_RAW is unset.
 */
class FribbelsConvertTest {
    @Test
    fun convertSavedResponse() {
        val raw = System.getenv("E7_RAW")
        assumeTrue(raw != null)
        val r = Fribbels.convert(JSONObject(File(raw!!).readText()))
        File(System.getenv("E7_OUT")!!).writeText(JSONObject().put("items", r.items).put("heroes", r.heroes).toString())
    }
}
