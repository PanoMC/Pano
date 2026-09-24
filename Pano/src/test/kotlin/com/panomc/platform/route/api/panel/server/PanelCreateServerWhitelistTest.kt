package com.panomc.platform.route.api.panel.server

import com.panomc.platform.server.ServerCreateSource
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanelCreateServerWhitelistTest {
    @Test
    fun `a server Pano builds gets the whitelist written, off unless asked for`() {
        assertEquals(mapOf("white-list" to "false"), PanelCreateServerAPI.initialProperties(ServerCreateSource.FRESH, ServerType.PAPER, false))
        assertEquals(mapOf("white-list" to "true"), PanelCreateServerAPI.initialProperties(ServerCreateSource.FRESH, ServerType.FABRIC, true))
        assertEquals(mapOf("white-list" to "false"), PanelCreateServerAPI.initialProperties(ServerCreateSource.MODPACK, ServerType.VANILLA, false))
    }

    @Test
    fun `an imported server and a proxy keep what they have`() {
        listOf(ServerCreateSource.EXISTING_FOLDER, ServerCreateSource.IN_PLACE, ServerCreateSource.UPLOAD).forEach { source ->
            assertEquals(emptyMap<String, String>(), PanelCreateServerAPI.initialProperties(source, ServerType.PAPER, true))
        }

        assertEquals(emptyMap<String, String>(), PanelCreateServerAPI.initialProperties(ServerCreateSource.FRESH, ServerType.VELOCITY, false))
    }
}
