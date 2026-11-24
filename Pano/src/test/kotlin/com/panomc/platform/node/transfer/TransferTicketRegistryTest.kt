package com.panomc.platform.node.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    private fun issueUpload(registry: TransferTicketRegistry, ttlMs: Long = TransferTicketRegistry.TTL_MS) = registry.issue(
        userId = 3,
        serverId = 33,
        nodeId = 7,
        serverUuid = "server-uuid",
        path = "",
        direction = TransferDirection.UPLOAD,
        fileName = "world.zip",
        ttlMs = ttlMs,
        upload = UploadRendezvous(1024)
    )

    @Test
    fun `hands an upload ticket only to the person who created it, and only before its bytes started`() {
        val registry = TransferTicketRegistry()
        val ticket = issueUpload(registry)

        assertNull(registry.resolveUpload(ticket.id, 4))
        assertNotNull(registry.resolveUpload(ticket.id, 3))

        ticket.upload!!.source.completeExceptionally(IllegalStateException("taken"))

        assertNull(registry.resolveUpload(ticket.id, 3))
    }

    @Test
    fun `an upload ticket without a rendezvous is not a browser upload`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, direction = TransferDirection.UPLOAD)

        assertNull(registry.resolveUpload(ticket.id, 3))
    }

    @Test
    fun `an expired upload ticket releases whoever waited at its rendezvous`() {
        val registry = TransferTicketRegistry()
        val ticket = issueUpload(registry, ttlMs = -1)

        registry.sweep()

        assertTrue(ticket.upload!!.source.isCompleted)
        assertTrue(ticket.upload!!.delivered.isCompleted)
        assertEquals(0, registry.size())
    }

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
    fun `a download that has started is not expired under it`() {
        val registry = TransferTicketRegistry()
        val ticket = issue(registry, ttlMs = -1)

        ticket.started = true

        registry.sweep()

        assertEquals(1, registry.size())
        assertTrue(!ticket.completion.isCompleted)
        assertNotNull(registry.resolve(ticket.id, 7, TransferDirection.DOWNLOAD))
    }

    @Test
    fun `waiting on a download covers the start only`() = runBlocking {
        val registry = TransferTicketRegistry()

        // Never started: gives up at the ticket's limit.
        assertNull(issue(registry, ttlMs = 50).awaitDownload())

        // Started: waits past the limit until the stream ends.
        val running = issue(registry, ttlMs = 50).apply { started = true }

        launch {
            delay(300)
            running.completion.complete(42L)
        }

        assertEquals(42L, running.awaitDownload())
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
