package com.panomc.platform.mail

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MailSmtpTestConfigTest {
    @Test
    fun `only SMTP fields reach the mail client`() {
        val raw = JsonObject()
            .put("hostname", "smtp.example.com")
            .put("port", 587)
            .put("ssl", false)
            .put("starttls", "REQUIRED")
            .put("username", "user")
            .put("password", "secret")
            .put("authMethods", "PLAIN LOGIN")
            .put("sender", "a@example.com")
            .put("proxyOptions", JsonObject().put("host", "evil.example").put("port", 8080).put("type", "HTTP"))
            .put("trustAll", true)
            .put("login", "REQUIRED")

        val safe = MailManager.smtpTestConfig(raw)!!

        assertEquals(
            setOf("hostname", "port", "ssl", "starttls", "username", "password", "authMethods"),
            safe.fieldNames()
        )
    }

    @Test
    fun `malformed fields are refused`() {
        assertNull(MailManager.smtpTestConfig(JsonObject().put("hostname", "")))
        assertNull(MailManager.smtpTestConfig(JsonObject().put("hostname", "h").put("port", 70000)))
        assertNull(MailManager.smtpTestConfig(JsonObject().put("hostname", "h").put("starttls", "MAYBE")))
        assertNull(MailManager.smtpTestConfig(JsonObject().put("hostname", "h").put("authMethods", "PLAIN EVIL")))
    }

    @Test
    fun `failures are reported as a short reason`() {
        assertEquals("CONNECTION_FAILED", MailManager.smtpFailureReason(java.net.ConnectException("Connection refused: /10.0.0.1:22")))
        assertEquals("AUTHENTICATION_FAILED", MailManager.smtpFailureReason(Exception("535 5.7.8 Authentication failed")))
        assertEquals("SEND_FAILED", MailManager.smtpFailureReason(Exception("SSH-2.0-OpenSSH_9.6")))
    }
}
