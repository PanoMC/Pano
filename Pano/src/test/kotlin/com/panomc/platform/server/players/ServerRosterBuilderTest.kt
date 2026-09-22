package com.panomc.platform.server.players

import com.panomc.platform.db.model.ServerPlayer
import com.panomc.platform.server.dto.ServerMetricPlayerData
import com.panomc.platform.server.dto.ServerMetricSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class ServerRosterBuilderTest {
    private val steve = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val alex = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun sample(vararg players: ServerMetricPlayerData) = ServerMetricSample(
        t = 0, tps = null, mspt = null, memUsed = 0, memMax = 0, cpu = null,
        playerCount = players.size.toLong(), maxPlayerCount = 20, players = players.toList()
    )

    @Test
    fun `op, whitelist and game mode come from the sample, account from the name`() {
        val roster = ServerRosterBuilder.build(
            listOf(
                ServerPlayer(uuid = steve, username = "Steve", serverId = 1, loginTime = 5),
                ServerPlayer(uuid = alex, username = "Alex", serverId = 1, loginTime = 6)
            ),
            sample(ServerMetricPlayerData(steve.toString(), "Steve", 40, op = true, whitelisted = false, gamemode = "creative")),
            setOf("steve")
        )

        val (alexRow, steveRow) = roster

        assertEquals(true, steveRow.getBoolean("op"))
        assertEquals(false, steveRow.getBoolean("whitelisted"))
        assertEquals("creative", steveRow.getString("gamemode"))
        assertEquals(40L, steveRow.getLong("ping"))
        assertEquals(true, steveRow.getBoolean("panoUser"))

        // Not in the sample yet (joined a moment ago): unknown, not "not an op".
        assertNull(alexRow.getBoolean("op"))
        assertNull(alexRow.getString("gamemode"))
        assertEquals(false, alexRow.getBoolean("panoUser"))
    }
}
