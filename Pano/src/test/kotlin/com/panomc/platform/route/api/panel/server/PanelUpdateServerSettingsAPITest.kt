package com.panomc.platform.route.api.panel.server

import com.panomc.platform.db.model.Server
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `PUT /api/panel/servers/:id/settings` and the automatic update check (SM-69, §2.4.34). */
class PanelUpdateServerSettingsAPITest {
    @Test
    fun `the switch can be turned off and on again`() {
        val settings = Server.Companion.ServerSettings()

        PanelUpdateServerSettingsAPI.applySettings(settings, JsonObject().put("autoUpdateCheck", false))
        assertFalse(settings.autoUpdateCheck)

        PanelUpdateServerSettingsAPI.applySettings(settings, JsonObject().put("autoUpdateCheck", true))
        assertTrue(settings.autoUpdateCheck)
    }

    @Test
    fun `an older panel that sends the five integration switches keeps the stored value`() {
        val settings = Server.Companion.ServerSettings(autoUpdateCheck = false)

        PanelUpdateServerSettingsAPI.applySettings(
            settings,
            JsonObject()
                .put("authIntegration", false)
                .put("authRequireVerified", false)
                .put("authKickAfterRegister", false)
                .put("banIntegration", false)
                .put("permissionIntegration", false)
        )

        assertFalse(settings.autoUpdateCheck)
        assertFalse(settings.authIntegration)
        assertFalse(settings.permissionIntegration)
    }

    @Test
    fun `the preferences page sending the switch alone leaves the integration switches alone`() {
        val settings = Server.Companion.ServerSettings(authIntegration = false, banIntegration = false)

        PanelUpdateServerSettingsAPI.applySettings(settings, JsonObject().put("autoUpdateCheck", false))

        assertFalse(settings.authIntegration)
        assertFalse(settings.banIntegration)
        assertTrue(settings.authRequireVerified)
        assertFalse(settings.autoUpdateCheck)
    }

    @Test
    fun `the backup limits are not the settings endpoint's to touch`() {
        val settings = Server.Companion.ServerSettings(backupKeepLast = 3, snapshotKeepLast = 7)

        PanelUpdateServerSettingsAPI.applySettings(settings, JsonObject().put("autoUpdateCheck", false))

        assertEquals(3, settings.backupKeepLast)
        assertEquals(7, settings.snapshotKeepLast)
    }

    @Test
    fun `only the integration switches are re-sent to the plugin`() {
        assertFalse("autoUpdateCheck" in PanelUpdateServerSettingsAPI.PLUGIN_SETTINGS)
        assertEquals(5, PanelUpdateServerSettingsAPI.PLUGIN_SETTINGS.size)
    }
}
