package com.panomc.platform.db.model

import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `settings` JSON blob, old rows and new (SM-69, §2.4.34). */
class ServerSettingsTest {
    @Test
    fun `a row written before the switch existed reads as on`() {
        // What a server row's `settings` column held before SM-69, read the way the DAO reads it.
        val stored = """{"authIntegration":false,"authRequireVerified":true,"authKickAfterRegister":true,""" +
                """"banIntegration":true,"permissionIntegration":true,"backupKeepLast":5}"""

        val settings = JsonObject(stored).mapTo(Server.Companion.ServerSettings::class.java)

        assertTrue(settings.autoUpdateCheck)
        assertFalse(settings.authIntegration)
        assertEquals(5, settings.backupKeepLast)
    }

    @Test
    fun `the switch survives a round trip through the column`() {
        val encoded = Server.Companion.ServerSettings(autoUpdateCheck = false).encode()

        assertFalse(JsonObject(encoded).getBoolean("autoUpdateCheck"))
        assertFalse(JsonObject(encoded).mapTo(Server.Companion.ServerSettings::class.java).autoUpdateCheck)
    }

    @Test
    fun `every server JSON carries the switch`() {
        val json = server(Server.Companion.ServerSettings(autoUpdateCheck = false)).toPublicJsonObject()

        assertFalse(json.getJsonObject("settings").getBoolean("autoUpdateCheck"))
        assertTrue(server(Server.Companion.ServerSettings()).toPublicJsonObject()
            .getJsonObject("settings").getBoolean("autoUpdateCheck"))
    }

    private fun server(settings: Server.Companion.ServerSettings) = Server(
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
        kind = ServerKind.MANAGED,
        settings = settings
    )
}
