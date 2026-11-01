package e7.gearscan

import e7.gearscan.data.KnownIssues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class KnownIssuesTest {
    private val reply = """{"updated":"2026-11-01","issues":[
        {"id":"a","app":"gear","title":"Import error","detail":"Capture kept","status":"known","fixed_on":null},
        {"id":"b","app":"gear","title":"Working one","status":"working"},
        {"id":"c","app":"gear","title":"Done","detail":"","status":"fixed","fixed_on":"2026-10-30"},
        {"id":"d","app":"gear","title":"Odd","status":"maybe"},
        {"id":"e","app":"gear","title":"  ","status":"known"},
        "not an object"]}"""

    @Test fun parsesAndSkipsEntriesThatDontFit() {
        val issues = KnownIssues.parse(reply)!!
        assertEquals(listOf("a", "b", "c"), issues.map { it.id })
        assertEquals(listOf("Known", "Working on it", "Fixed · 2026-10-30"), issues.map { it.label })
        assertEquals("Capture kept", issues[0].detail)
        assertEquals("", issues[1].detail)
        assertNull(issues[0].fixedOn)
    }

    @Test fun notAReplyIsNull() {
        assertNull(KnownIssues.parse("<html>"))
        assertNull(KnownIssues.parse("""{"error":"x"}"""))
        assertEquals(emptyList<Any>(), KnownIssues.parse("""{"issues":[]}"""))
    }

    @Test fun urlComesFromTheReportsAddress() {
        assertEquals("https://gw.example/api/known-issues?app=gear", KnownIssues.url("https://gw.example/api/app-feedback"))
        assertNull(KnownIssues.url(null))
        assertNull(KnownIssues.url("https://gw.example/other"))
    }

    @Test fun cacheMissingOrBrokenIsEmpty() {
        val dir = Files.createTempDirectory("ki").toFile()
        assertEquals(emptyList<Any>(), KnownIssues.cached(dir))
        dir.resolve("known_issues.json").writeText("{broken")
        assertEquals(emptyList<Any>(), KnownIssues.cached(dir))
        dir.resolve("known_issues.json").writeText(reply)
        assertEquals(3, KnownIssues.cached(dir).size)
    }
}
