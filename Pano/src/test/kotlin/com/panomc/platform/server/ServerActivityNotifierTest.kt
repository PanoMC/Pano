package com.panomc.platform.server

import com.panomc.platform.auth.panel.log.ServerCrashedLog
import com.panomc.platform.auth.panel.log.ServerPowerActionLog
import com.panomc.platform.db.model.PanelActivityLog
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The choke point behind the live "Recent activity" feed. */
class ServerActivityNotifierTest {
    private val announced = mutableListOf<Long>()

    @AfterEach
    fun reset() {
        ServerActivityNotifier.sink = null
    }

    @Test
    fun `a server-scoped entry is announced with its server id`() {
        ServerActivityNotifier.sink = { announced.add(it) }

        ServerActivityNotifier.onLogAdded(ServerPowerActionLog(1, "admin", 42, "restart"))
        // Node-originated, no user behind it: still that server's history.
        ServerActivityNotifier.onLogAdded(ServerCrashedLog(7, "lobby", 137))

        assertEquals(listOf(42L, 7L), announced)
    }

    @Test
    fun `entries the server activity feed would not return are not announced`() {
        ServerActivityNotifier.sink = { announced.add(it) }

        // A serverId on a type outside the feed.
        ServerActivityNotifier.onLogAdded(PanelActivityLog(type = "UPDATED_WEBSITE_SETTINGS", details = JsonObject().put("serverId", 42)))
        // A feed type without a usable serverId.
        ServerActivityNotifier.onLogAdded(PanelActivityLog(type = ServerActivityLogTypes.ALL.first(), details = JsonObject()))
        ServerActivityNotifier.onLogAdded(PanelActivityLog(type = ServerActivityLogTypes.ALL.first(), details = JsonObject().put("serverId", "x")))

        assertEquals(emptyList<Long>(), announced)
        assertNull(ServerActivityNotifier.serverIdOf(PanelActivityLog(type = "X", details = JsonObject().put("serverId", 1))))
    }

    @Test
    fun `no sink and a failing sink never fail the write`() {
        ServerActivityNotifier.onLogAdded(ServerCrashedLog(7, "lobby", 1))

        ServerActivityNotifier.sink = { throw IllegalStateException("socket gone") }

        ServerActivityNotifier.onLogAdded(ServerCrashedLog(7, "lobby", 1))
    }

    @Test
    fun `every server activity type is covered`() {
        assertEquals(ServerActivityLogTypes.ALL.size, ServerActivityLogTypes.ALL.toSet().size)
        assertEquals(42L, ServerActivityNotifier.serverIdOf(ServerPowerActionLog(1, "admin", 42, "stop")))
    }
}
