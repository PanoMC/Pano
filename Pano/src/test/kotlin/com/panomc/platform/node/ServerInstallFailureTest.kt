package com.panomc.platform.node

import com.panomc.platform.server.ServerPowerAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a failed install leaves on its server ([ServerInstallFailure]).
 *
 * The bug this pins down: a Spigot build failed, the reason was on the task for ten seconds, and
 * Start then did nothing, because the node had never registered the server and dropped the START.
 */
class ServerInstallFailureTest {
    private val reason = "No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?"

    @Test
    fun `a failed install or import keeps its reason on the row`() {
        assertEquals(reason, ServerInstallFailure.afterFailure(ServerTaskKind.INSTALL, null, reason))
        assertEquals(reason, ServerInstallFailure.afterFailure(ServerTaskKind.IMPORT, null, reason))
    }

    @Test
    fun `a failed reinstall of a working server leaves it startable`() {
        // The node put the old server back.
        assertNull(ServerInstallFailure.afterFailure(ServerTaskKind.REINSTALL, null, reason))
    }

    @Test
    fun `a failed reinstall of a failed install replaces the reason with the newer one`() {
        assertEquals(reason, ServerInstallFailure.afterFailure(ServerTaskKind.REINSTALL, "older reason", reason))
    }

    @Test
    fun `a failure with no reason still marks the row, trimmed and capped`() {
        assertEquals(ServerInstallFailure.UNKNOWN_ERROR, ServerInstallFailure.afterFailure(ServerTaskKind.INSTALL, null, null))
        assertEquals(ServerInstallFailure.UNKNOWN_ERROR, ServerInstallFailure.afterFailure(ServerTaskKind.INSTALL, null, "  "))
        assertEquals("x", ServerInstallFailure.afterFailure(ServerTaskKind.INSTALL, null, "  x\n"))
        assertEquals(
            ServerInstallFailure.MAX_ERROR_LENGTH,
            ServerInstallFailure.afterFailure(ServerTaskKind.INSTALL, null, "e".repeat(5000))!!.length
        )
    }

    @Test
    fun `other failed tasks leave the row alone`() {
        assertEquals("kept", ServerInstallFailure.afterFailure(ServerTaskKind.BACKUP, "kept", reason))
        assertNull(ServerInstallFailure.afterFailure(ServerTaskKind.PLUGIN_INSTALL, null, reason))
    }

    @Test
    fun `whatever puts a server on disk clears the reason when it is done`() {
        assertTrue(ServerInstallFailure.clearsOnDone(ServerTaskKind.INSTALL))
        assertTrue(ServerInstallFailure.clearsOnDone(ServerTaskKind.REINSTALL))
        assertTrue(ServerInstallFailure.clearsOnDone(ServerTaskKind.IMPORT))
        assertTrue(ServerInstallFailure.clearsOnDone(ServerTaskKind.RESTORE))
        assertFalse(ServerInstallFailure.clearsOnDone(ServerTaskKind.BACKUP))
    }

    @Test
    fun `only a start or restart of a failed install is refused`() {
        assertTrue(ServerInstallFailure.blocks(ServerPowerAction.START, reason))
        assertTrue(ServerInstallFailure.blocks(ServerPowerAction.RESTART, reason))
        assertFalse(ServerInstallFailure.blocks(ServerPowerAction.STOP, reason))
        assertFalse(ServerInstallFailure.blocks(ServerPowerAction.KILL, reason))
        assertFalse(ServerInstallFailure.blocks(ServerPowerAction.START, null))
    }
}
