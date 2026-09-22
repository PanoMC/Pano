package com.panomc.platform.server.metrics

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a measured directory size is worth a database write, and that the figure reaches the panel
 * once it is on the row (§2.4.18 B).
 *
 * The write rule is the load-bearing part: every server sends a metrics frame every ten seconds,
 * so "store it each time" would be a `UPDATE server` per server per tick, forever, for a number
 * that is re-measured every five minutes at the very most.
 */
class ServerDiskUsageStoreTest {
    @Test
    fun `the first measurement of a server is news`() {
        assertTrue(ServerDiskUsageStore.isNews(stored = null, measured = 41231953920))
    }

    @Test
    fun `the same figure again is not`() {
        assertFalse(ServerDiskUsageStore.isNews(stored = 41231953920, measured = 41231953920))
    }

    @Test
    fun `a directory that grew or shrank is`() {
        assertTrue(ServerDiskUsageStore.isNews(stored = 41231953920, measured = 41231953921))
        assertTrue(ServerDiskUsageStore.isNews(stored = 41231953920, measured = 10))
    }

    @Test
    fun `a tick with no measurement never erases the one on the row`() {
        // Null is "nobody has walked it yet", which happens on every node restart and for five
        // minutes after a restore — the row is where the last known size lives through all of it.
        assertFalse(ServerDiskUsageStore.isNews(stored = 41231953920, measured = null))
        assertFalse(ServerDiskUsageStore.isNews(stored = null, measured = null))
    }

    @Test
    fun `the disk behind a server is news on its own`() {
        // The two halves arrive apart: the partition is a system call, the directory is a walk
        // that has not finished yet.
        assertTrue(ServerDiskUsageStore.isNews(stored = null, measured = 500_107_862_016))
        assertFalse(ServerDiskUsageStore.isNews(stored = 500_107_862_016, measured = 500_107_862_016))
    }

    @Test
    fun `a figure with no measurement behind it keeps what the row already holds`() {
        assertEquals(41231953920L, ServerDiskUsageStore.keep(stored = 41231953920, measured = null))
        assertEquals(10L, ServerDiskUsageStore.keep(stored = 41231953920, measured = 10))
        assertNull(ServerDiskUsageStore.keep(stored = null, measured = null))
    }

    @Test
    fun `a server row carries the pair to the panel, and says nothing when it has neither`() {
        val json = server().apply {
            diskUsed = 41231953920
            diskTotal = 500_107_862_016
        }.toPublicJsonObject()

        assertEquals(41231953920L, json.getLong("diskUsed"))
        assertEquals(500_107_862_016L, json.getLong("diskTotal"))
        // The secret still never leaves.
        assertFalse(json.containsKey("aesKey"))

        assertNull(server().toPublicJsonObject().getLong("diskUsed"))
        assertNull(server().toPublicJsonObject().getLong("diskTotal"))
    }

    private fun server() = Server(
        name = "survival",
        motd = "",
        host = "127.0.0.1",
        port = 25565,
        playerCount = 0,
        maxPlayerCount = 20,
        type = ServerType.PAPER,
        version = "1.21.1",
        favicon = "",
        permissionGranted = true,
        status = ServerStatus.OFFLINE,
        startTime = 0,
        aesKey = "key",
        kind = ServerKind.MANAGED
    )
}
