package com.panomc.platform.server.plugins

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which servers the daily plugin sweep looks at (SM-69, §2.4.34). */
class PluginUpdateSweeperTest {
    @Test
    fun `a managed server is checked by default`() {
        assertEquals(PluginUpdateSweeper.Outcome.CHECKED, PluginUpdateSweeper.eligibilityOf(server(ServerKind.MANAGED)))
    }

    @Test
    fun `a managed server with the check off is skipped`() {
        assertEquals(
            PluginUpdateSweeper.Outcome.SWITCHED_OFF,
            PluginUpdateSweeper.eligibilityOf(server(ServerKind.MANAGED, autoUpdateCheck = false))
        )
    }

    @Test
    fun `a linked server is never the sweep's, switch or no switch`() {
        assertEquals(PluginUpdateSweeper.Outcome.NOT_ELIGIBLE, PluginUpdateSweeper.eligibilityOf(server(ServerKind.LINKED)))
        assertEquals(
            PluginUpdateSweeper.Outcome.NOT_ELIGIBLE,
            PluginUpdateSweeper.eligibilityOf(server(ServerKind.LINKED, autoUpdateCheck = false))
        )
    }

    private fun server(kind: ServerKind, autoUpdateCheck: Boolean = true) = Server(
        name = "test",
        motd = "",
        host = "127.0.0.1",
        port = 25565,
        playerCount = 0,
        maxPlayerCount = 0,
        type = ServerType.PAPER,
        version = "1.21.1",
        favicon = "",
        status = ServerStatus.OFFLINE,
        startTime = 0,
        aesKey = "key",
        kind = kind,
        settings = Server.Companion.ServerSettings(autoUpdateCheck = autoUpdateCheck)
    )
}
