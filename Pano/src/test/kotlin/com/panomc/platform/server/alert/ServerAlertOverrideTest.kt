package com.panomc.platform.server.alert

import com.panomc.platform.route.api.panel.server.PanelUpdateServerSettingsAPI
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerAlertOverrideTest {
    @Test
    fun `a server's own switch wins over the platform one, for server alerts only`() {
        val overrides = mapOf("SERVER_CRASHED" to false, "TPS_LOW" to true, "NODE_OFFLINE" to false)

        assertFalse(AlertManager.resolveEnabled(ServerAlertKind.SERVER_CRASHED, true, overrides))
        assertTrue(AlertManager.resolveEnabled(ServerAlertKind.TPS_LOW, false, overrides))
        // Not in the map: the platform switch.
        assertTrue(AlertManager.resolveEnabled(ServerAlertKind.BACKUP_FAILED, true, overrides))
        assertFalse(AlertManager.resolveEnabled(ServerAlertKind.BACKUP_FAILED, false, overrides))
        // A node alert is about the machine, not the server: never overridden per server.
        assertTrue(AlertManager.resolveEnabled(ServerAlertKind.NODE_OFFLINE, true, overrides))
        assertTrue(AlertManager.resolveEnabled(ServerAlertKind.SERVER_CRASHED, true, null))
    }

    @Test
    fun `the settings body sets, clears and ignores what it should`() {
        val current = mapOf("SERVER_CRASHED" to false, "BACKUP_FAILED" to true)

        val next = PanelUpdateServerSettingsAPI.mergeAlerts(
            current,
            JsonObject()
                .put("SERVER_CRASHED", true)
                .putNull("BACKUP_FAILED")
                .put("TPS_LOW", false)
                .put("DISK_LOW", false)
                .put("NOT_A_KIND", true)
                .put("SCHEDULE_FAILED", "yes")
        )

        assertEquals(mapOf("SERVER_CRASHED" to true, "TPS_LOW" to false), next)
        assertEquals(current, PanelUpdateServerSettingsAPI.mergeAlerts(current, null))
    }

    @Test
    fun `only the node alerts are platform-wide`() {
        assertEquals(
            setOf(ServerAlertKind.NODE_OFFLINE, ServerAlertKind.DISK_LOW),
            ServerAlertKind.entries.filterNot { it.serverScoped }.toSet()
        )
    }
}
