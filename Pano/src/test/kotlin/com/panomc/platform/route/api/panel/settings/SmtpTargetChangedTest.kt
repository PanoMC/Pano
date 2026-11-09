package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.config.PanoConfig
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmtpTargetChangedTest {
    private fun current() = PanoConfig.Companion.EmailConfig(
        enabled = true, provider = "SMTP", sender = "Pano <no-reply@site.com>",
        hostname = "smtp.site.com", port = 465, username = "no-reply@site.com", password = "secret"
    )

    private fun save(hostname: String = "smtp.site.com", port: Int = 465, username: String = "no-reply@site.com",
                     sender: String = "Pano <no-reply@site.com>", enabled: Boolean = true) =
        JsonObject().put("enabled", enabled).put("hostname", hostname).put("port", port)
            .put("username", username).put("sender", sender).put("password", "")

    @Test
    fun `saving the same server again needs no password`() {
        assertFalse(smtpTargetChanged(current(), save(), hostMailAvailable = false))
    }

    @Test
    fun `another server, port, account or sender needs the password`() {
        assertTrue(smtpTargetChanged(current(), save(hostname = "relay.evil.tld"), false))
        assertTrue(smtpTargetChanged(current(), save(port = 2525), false))
        assertTrue(smtpTargetChanged(current(), save(username = "x"), false))
        assertTrue(smtpTargetChanged(current(), save(sender = "x@evil.tld"), false))
    }

    @Test
    fun `leaving Pano Mail for SMTP needs the password`() {
        assertTrue(smtpTargetChanged(current().apply { provider = "PANO_MAIL" }, save(), false))
    }

    @Test
    fun `turning mail off or using Pano Host mail needs none`() {
        assertFalse(smtpTargetChanged(current(), save(hostname = "other", enabled = false), false))
        assertFalse(smtpTargetChanged(current(), save(hostname = "other").put("hostManaged", true), true))
    }
}
