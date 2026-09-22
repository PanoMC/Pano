package com.panomc.platform.db.model

import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerPendingApprovalTest {
    @Test
    fun `an unapproved linked server is a pending connect request`() {
        assertTrue(server(kind = ServerKind.LINKED, permissionGranted = false).isPendingApproval)
    }

    @Test
    fun `an approved linked server is not pending`() {
        assertFalse(server(kind = ServerKind.LINKED, permissionGranted = true).isPendingApproval)
    }

    @Test
    fun `a managed server is never pending, approved or not`() {
        assertFalse(server(kind = ServerKind.MANAGED, permissionGranted = true).isPendingApproval)
        // Even a row that somehow lost its approval flag: Pano created it, so there is no request
        // for an admin to accept or reject and rejecting it would only delete the row.
        assertFalse(server(kind = ServerKind.MANAGED, permissionGranted = false).isPendingApproval)
    }

    private fun server(kind: ServerKind, permissionGranted: Boolean) = Server(
        name = "test",
        motd = "",
        host = "127.0.0.1",
        port = 25565,
        playerCount = 0,
        maxPlayerCount = 0,
        type = ServerType.PAPER,
        version = "1.21.1",
        favicon = "",
        permissionGranted = permissionGranted,
        status = ServerStatus.OFFLINE,
        startTime = 0,
        aesKey = "key",
        kind = kind
    )
}
