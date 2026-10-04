package com.panomc.platform.server

import com.google.gson.Gson
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.dto.NodeServerStateData
import com.panomc.platform.node.event.request.ServerStateEventRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a managed server's process started, which is what its uptime is counted from when it has no
 * Pano plugin: set on RUNNING, kept while the same process is there, cleared the moment it is not.
 */
class ProcessStartTimeTest {
    private val now = 1_758_640_000_000L

    @Test
    fun `running takes the node's own start time`() {
        assertEquals(
            1_758_630_000_000L,
            ProcessStartTime.next(null, ServerProcessState.RUNNING, startedAt = 1_758_630_000_000, since = 1_758_630_050_000, now = now)
        )
    }

    @Test
    fun `an older node without one falls back to when it became running, then to now`() {
        assertEquals(1_758_630_050_000L, ProcessStartTime.whenRunning(null, since = 1_758_630_050_000, now = now))
        assertEquals(now, ProcessStartTime.whenRunning(null, null, now))
        assertEquals(now, ProcessStartTime.whenRunning(0, -5, now), "a start time that is not one is ignored")
    }

    @Test
    fun `stopping keeps it, because the same process is still there`() {
        assertEquals(123L, ProcessStartTime.next(123, ServerProcessState.STOPPING, null, null, now))
        assertTrue(ProcessStartTime.keeps(ServerProcessState.RUNNING))
        assertTrue(ProcessStartTime.keeps(ServerProcessState.STOPPING))
    }

    @Test
    fun `every state without a live process clears it`() {
        listOf(
            ServerProcessState.STOPPED,
            ServerProcessState.CRASHED,
            ServerProcessState.STARTING,
            ServerProcessState.INSTALLING
        ).forEach { state ->
            assertNull(ProcessStartTime.next(123, state, 456, 789, now), state.name)
            assertFalse(ProcessStartTime.keeps(state), state.name)
        }

        // A server Pano knows nothing about the state of is not running either.
        assertFalse(ProcessStartTime.keeps(null))
    }

    @Test
    fun `the node's frames carry it`() {
        val state = Gson().fromJson(
            """{"serverUuid":"abc","state":"RUNNING","since":2000,"startedAt":1000}""",
            ServerStateEventRequest::class.java
        )

        assertEquals(1000L, state.startedAt)

        val hello = Gson().fromJson("""{"uuid":"abc","state":"RUNNING","startedAt":1000}""", NodeServerStateData::class.java)

        assertEquals(1000L, hello.startedAt)

        // And an older node's simply do not.
        assertNull(Gson().fromJson("""{"state":"RUNNING"}""", ServerStateEventRequest::class.java).startedAt)
    }

    @Test
    fun `the panel reads it off the server row as processStartedAt`() {
        val server = Server(
            name = "survival",
            motd = "",
            host = "127.0.0.1",
            port = 25565,
            playerCount = 0,
            maxPlayerCount = 20,
            type = ServerType.PAPER,
            version = "1.21.8",
            favicon = "",
            permissionGranted = true,
            status = ServerStatus.OFFLINE,
            startTime = 0,
            aesKey = "key",
            kind = ServerKind.MANAGED,
            processStartedAt = 1_758_630_000_000
        )

        assertEquals(1_758_630_000_000L, server.toPublicJsonObject().getLong("processStartedAt"))
        assertNull(server.copy(processStartedAt = null).toPublicJsonObject().getLong("processStartedAt"))
    }
}
