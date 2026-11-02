package e7.gearscan

import e7.gearscan.ui.Confirmed
import e7.gearscan.ui.Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun userConfirmed(): Confirmed {
    var token: Confirmed? = null
    val m = Messages()
    m.confirm("Sure?", "Really.", "Do it") { token = it }
    m.dialog!!.run!!.invoke()
    return token!!
}

class MessagesTest {
    @Test fun confirmIssuesATokenOnlyWhenTheDialogIsConfirmed() {
        var issued = 0
        val m = Messages()
        m.confirm("Erase?", "Gone.", "Erase") { issued++ }
        assertEquals("Erase", m.dialog!!.action)
        assertEquals(0, issued)
        assertTrue(m.dismissConfirm())
        assertEquals(0, issued)
        assertNull(m.dialog)
        m.confirm("Erase?", "Gone.", "Erase") { issued++ }
        m.dialog!!.run!!.invoke()
        assertEquals(1, issued)
    }

    @Test fun askResetPassesTheTokenOn() {
        var got: Confirmed? = null
        val m = Messages()
        m.askReset("Reset?", "Back to default.") { got = it }
        assertEquals("Reset", m.dialog!!.action)
        assertNull(got)
        m.dialog!!.run!!.invoke()
        assertNotNull(got)
    }
}
