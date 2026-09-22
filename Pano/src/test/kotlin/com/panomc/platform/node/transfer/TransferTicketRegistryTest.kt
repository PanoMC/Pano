package com.panomc.platform.node.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransferTicketRegistryTest {
    private fun issue(
        registry: TransferTicketRegistry,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        nodeId: Long? = 7,
        ttlMs: Long = TransferTicketRegistry.TTL_MS
    ) = registry.issue(
        userId = 3,
        serverId = 33,
        nodeId = nodeId,
        serverUuid = "server-uuid",
        path = "plugins/LuckPerms/config.yml",
        direction = direction,
        fileName = "config.yml",
        ttlMs = ttlMs
    )

    @Test
    fun `redeems a live ticket for the right node and direction`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry)

        assertNotNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `refuses a ticket issued for another node`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, nodeId = 7)

        assertNull(registry.resolve(ticket.id, 8, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `refuses a ticket used in the other direction`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, direction = TransferDirection.UPLOAD)

        assertNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `refuses an expired ticket and drops it`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, ttlMs = -1)

        assertTrue(ticket.isExpired)
        assertNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
        assertEquals(0, registry.size())
    }

    @Test
    fun `refuses a ticket that does not exist`() {
        val registry = TransferTicketRegistry()

        assertNull(registry.resolve("nope", 7, TransferDirection.DOWNLOAD))
        assertNull(registry.resolve(null, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `hands a consumed ticket out only once`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry)

        assertNotNull(registry.consume(ticket.id))
        assertNull(registry.consume(ticket.id))
        assertNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `sweeping tells whoever was waiting and cleans up`() {
        var discarded = 0

        val registry = TransferTicketRegistry { discarded++ }
        val ticket = issue(registry, ttlMs = -1)

        registry.sweep()

        assertEquals(1, discarded)
        assertEquals(0, registry.size())
        assertTrue(ticket.completion.isCompleted)
    }

    @Test
    fun `keeps a ticket that is still live`() {
        val registry = TransferTicketRegistry()

        issue(registry)

        registry.sweep()

        assertEquals(1, registry.size())
    }

    // ------------------------------------------------- a ticket a server's own plugin redeems

    @Test
    fun `a plugin ticket is redeemed by its server and by nothing else`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, nodeId = null)

        assertNotNull(registry.resolveForServer(ticket.id, 33, TransferDirection.DOWNLOAD))
        assertNull(registry.resolveForServer(ticket.id, 34, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `a node cannot redeem a ticket meant for a plugin`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, nodeId = null)

        assertNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `a plugin cannot redeem a ticket meant for a node`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, nodeId = 7)

        assertNull(registry.resolveForServer(ticket.id, 33, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `a plugin ticket still has to be live and pointing the right way`() {
        val registry = TransferTicketRegistry()

        assertNull(
            registry.resolveForServer(
                issue(registry, nodeId = null, direction = TransferDirection.UPLOAD).id,
                33,
                TransferDirection.DOWNLOAD
            )
        )

        assertNull(
            registry.resolveForServer(
                issue(registry, nodeId = null, ttlMs = -1).id,
                33,
                TransferDirection.DOWNLOAD
            )
        )
    }
}
