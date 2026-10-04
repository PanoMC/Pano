package com.panomc.platform.server.alert

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertSettingsTest {
    @Test
    fun `defaults to every kind on and nothing by email`() {
        val defaults = AlertSettings.defaults()

        assertEquals(ServerAlertKind.entries.size, defaults.size)
        assertTrue(defaults.values.all { it.enabled })
        assertFalse(defaults.values.any { it.email })
    }

    @Test
    fun `a missing, empty or malformed row falls back to the defaults`() {
        listOf(null, "", "   ", "not json at all", "[]").forEach { stored ->
            val settings = AlertSettings.parse(stored)

            assertTrue(settings.values.all { it.enabled }, "expected defaults for \"$stored\"")
        }
    }

    @Test
    fun `reads what was stored and keeps the defaults for what was not`() {
        val stored = JsonObject()
            .put(ServerAlertKind.TPS_LOW.name, JsonObject().put("enabled", false).put("email", true))
            .encode()

        val settings = AlertSettings.parse(stored)

        assertFalse(settings.getValue(ServerAlertKind.TPS_LOW).enabled)
        assertTrue(settings.getValue(ServerAlertKind.TPS_LOW).email)
        assertTrue(settings.getValue(ServerAlertKind.DISK_LOW).enabled)
        assertFalse(settings.getValue(ServerAlertKind.DISK_LOW).email)
    }

    @Test
    fun `ignores a kind it does not know`() {
        val stored = JsonObject()
            .put("SOMETHING_ELSE", JsonObject().put("enabled", false))
            .encode()

        val settings = AlertSettings.parse(stored)

        assertEquals(ServerAlertKind.entries.size, settings.size)
        assertTrue(settings.values.all { it.enabled })
    }

    @Test
    fun `round-trips through the panel's shape`() {
        val settings = AlertSettings.parse(
            JsonObject()
                .put(ServerAlertKind.NODE_OFFLINE.name, JsonObject().put("enabled", false).put("email", true))
                .encode()
        )

        val roundTripped = AlertSettings.fromJson(AlertSettings.toJson(settings))

        assertEquals(settings, roundTripped)
    }

    @Test
    fun `every kind has a cooldown that is actually a cooldown`() {
        assertTrue(ServerAlertKind.entries.all { it.cooldownMs > 0 })
    }
}
