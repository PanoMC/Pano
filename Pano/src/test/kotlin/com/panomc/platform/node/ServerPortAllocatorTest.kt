package com.panomc.platform.node

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ServerPortAllocatorTest {
    private fun server(port: Int, gamePort: Int?, processState: ServerProcessState = ServerProcessState.RUNNING) =
        Server(
            name = "test",
            motd = "",
            host = "127.0.0.1",
            port = port,
            playerCount = 0,
            maxPlayerCount = 0,
            type = ServerType.PAPER,
            version = "1.21.8",
            favicon = "",
            status = ServerStatus.OFFLINE,
            startTime = 0,
            aesKey = "",
            kind = ServerKind.MANAGED,
            gamePort = gamePort,
            processState = processState
        )

    @Test
    fun `walks up from the Minecraft default`() {
        assertEquals(listOf(25565, 25566, 25567), ServerPortAllocator.allocate(3, emptySet()))
    }

    @Test
    fun `skips ports the node already holds`() {
        assertEquals(
            listOf(25567, 25569),
            ServerPortAllocator.allocate(2, setOf(25565, 25566, 25568))
        )
    }

    @Test
    fun `never hands the same port out twice in one call`() {
        val ports = ServerPortAllocator.allocate(5, emptySet())!!

        assertEquals(ports.size, ports.toSet().size)
    }

    @Test
    fun `asking for nothing allocates nothing`() {
        assertEquals(emptyList<Int>(), ServerPortAllocator.allocate(0, setOf(25565)))
    }

    @Test
    fun `refuses rather than returning a short list when the range runs out`() {
        assertNull(ServerPortAllocator.allocate(3, setOf(25565), from = 25565, to = 25567))
    }

    @Test
    fun `honours a narrowed range`() {
        assertEquals(listOf(30000, 30001), ServerPortAllocator.allocate(2, emptySet(), from = 30000, to = 30010))
    }

    @Test
    fun `counts a port whichever column a server keeps it in`() {
        val servers = listOf(
            server(port = 25565, gamePort = 25565),
            // A linked server from before managed servers existed: only the address it is on.
            server(port = 25570, gamePort = null),
            // A managed row still installing: reserved on this side, nothing listening yet.
            server(port = 25566, gamePort = 25566, processState = ServerProcessState.INSTALLING)
        )

        assertEquals(setOf(25565, 25566, 25570), ServerPortAllocator.takenPorts(servers))
    }

    @Test
    fun `ignores a row that has no port at all`() {
        assertEquals(emptySet<Int>(), ServerPortAllocator.takenPorts(listOf(server(port = 0, gamePort = null))))
        assertEquals(emptySet<Int>(), ServerPortAllocator.takenPorts(emptyList()))
    }

    @Test
    fun `a new server allocates around the ports managed rows already reserved`() {
        // The split brain this closes: 25567 looked free to the create, because the concurrent
        // import's row carried no port of its own.
        val taken = ServerPortAllocator.takenPorts(
            listOf(
                server(port = 25565, gamePort = 25565),
                server(port = 25566, gamePort = 25566),
                server(port = 25567, gamePort = 25567, processState = ServerProcessState.INSTALLING)
            )
        )

        assertEquals(listOf(25568, 25569), ServerPortAllocator.allocate(2, taken))
    }

    @Test
    fun `allocates inside the range the node announced`() {
        // The live bug: a Coolify node publishing 25660-25669 was handed 25565.
        assertEquals(listOf(25660), ServerPortAllocator.allocate(1, emptySet(), 25660..25669))
        assertEquals(listOf(25662, 25664), ServerPortAllocator.allocate(2, setOf(25660, 25661, 25663), 25660..25669))
        // Ports the node's servers hold outside the range do not matter to it.
        assertEquals(listOf(25660), ServerPortAllocator.allocate(1, setOf(25565, 25566), 25660..25669))
    }

    @Test
    fun `a full node range is a refusal, never a port outside it`() {
        assertNull(ServerPortAllocator.allocate(1, setOf(25660, 25661), 25660..25661))
        assertNull(ServerPortAllocator.allocate(3, emptySet(), 25660..25661))
        assertEquals(listOf(25565), ServerPortAllocator.allocate(1, emptySet(), 25565..25565))
    }

    @Test
    fun `a node that announced no range is allocated as before`() {
        assertEquals(listOf(25565, 25566), ServerPortAllocator.allocate(2, emptySet(), null))
        assertEquals(ServerPortAllocator.allocate(2, setOf(25565, 25567)), ServerPortAllocator.allocate(2, setOf(25565, 25567), null))
    }

    @Test
    fun `the refusal names the range that ran out`() {
        assertEquals(
            "No free port is left in this node's port range (25660-25669).",
            ServerPortAllocator.noFreePortMessage(25660..25669)
        )
        assertEquals("No free port is left on this node.", ServerPortAllocator.noFreePortMessage(null))
    }
}
