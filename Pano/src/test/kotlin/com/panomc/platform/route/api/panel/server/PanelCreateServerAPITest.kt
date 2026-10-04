package com.panomc.platform.route.api.panel.server

import com.panomc.platform.db.model.Server
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The settings a server created by the wizard starts with (SM-69, §2.4.34). */
class PanelCreateServerAPITest {
    @Test
    fun `the automatic update check is on unless the wizard says otherwise`() {
        assertTrue(PanelCreateServerAPI.initialSettings(JsonObject()).autoUpdateCheck)
        assertTrue(PanelCreateServerAPI.initialSettings(JsonObject().put("autoUpdateCheck", true)).autoUpdateCheck)
        assertFalse(PanelCreateServerAPI.initialSettings(JsonObject().put("autoUpdateCheck", false)).autoUpdateCheck)
    }

    @Test
    fun `everything else starts at its default`() {
        val settings = PanelCreateServerAPI.initialSettings(JsonObject().put("autoUpdateCheck", false))

        assertEquals(Server.Companion.ServerSettings(autoUpdateCheck = false), settings)
    }
}
