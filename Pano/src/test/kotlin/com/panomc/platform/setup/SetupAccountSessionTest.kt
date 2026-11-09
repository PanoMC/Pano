package com.panomc.platform.setup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupAccountSessionTest {
    @Test
    fun `nothing matches before an account was connected`() {
        val session = SetupAccountSession()

        assertFalse(session.matches(null as String?))
        assertFalse(session.matches(""))
        assertFalse(session.matches("anything"))
    }

    @Test
    fun `only the browser that connected the account matches`() {
        val session = SetupAccountSession()
        session.issueForTest("browser-token")

        assertTrue(session.matches("browser-token"))
        assertFalse(session.matches("other-token"))
        assertFalse(session.matches(null as String?))
    }

    @Test
    fun `clearing drops the bound browser`() {
        val session = SetupAccountSession()
        session.issueForTest("browser-token")
        session.clear()

        assertFalse(session.matches("browser-token"))
    }
}
