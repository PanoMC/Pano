package com.panomc.platform.server

import com.google.gson.Gson
import com.panomc.platform.server.event.ChangePasswordEvent
import com.panomc.platform.server.event.request.ChangePasswordEventRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChangePasswordProtocolTest {
    private fun parse(json: String) = Gson().fromJson(json, ChangePasswordEventRequest::class.java)

    @Test
    fun `an old plugin's request still parses and needs no current password`() {
        val old = parse("""{"event":"CHANGE_PASSWORD","eventId":"00000000-0000-4000-8000-000000000001","username":"Steve","password":"newpass1"}""")

        assertNull(old.currentPassword)
        assertNull(old.reset)
        // Old plugin = password-check not negotiated.
        assertFalse(ChangePasswordEvent.requiresCurrentPassword(false, old))
    }

    @Test
    fun `a new plugin must send the current password unless it is a reset`() {
        val change = parse("""{"username":"Steve","password":"newpass1","currentPassword":"oldpass1"}""")
        val reset = parse("""{"username":"Steve","password":"newpass1","reset":true}""")
        val missing = parse("""{"username":"Steve","password":"newpass1"}""")

        assertEquals("oldpass1", change.currentPassword)
        assertTrue(ChangePasswordEvent.requiresCurrentPassword(true, change))
        assertTrue(ChangePasswordEvent.requiresCurrentPassword(true, missing))
        assertFalse(ChangePasswordEvent.requiresCurrentPassword(true, reset))
    }

    @Test
    fun `password-check is offered and agreed without channel v2 too`() {
        assertEquals(setOf("password-check"), SecureChannel.negotiate("password-check", null).capabilities)
        assertEquals(
            setOf("channel-v2", "password-check"),
            SecureChannel.negotiate("channel-v2,password-check", java.util.Base64.getEncoder().encodeToString(ByteArray(32))).capabilities
        )
    }
}
