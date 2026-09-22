package com.panomc.platform.server.backup

import com.panomc.platform.route.api.panel.server.backups.PanelRestoreServerBackupAPI
import com.panomc.platform.server.ServerProcessState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestoreGuardTest {
    @Test
    fun `allows a restore only when nothing is holding the files`() {
        assertTrue(PanelRestoreServerBackupAPI.isStopped(ServerProcessState.STOPPED))
        assertTrue(PanelRestoreServerBackupAPI.isStopped(ServerProcessState.CRASHED))
        // A row no node has reported on yet has no process behind it either.
        assertTrue(PanelRestoreServerBackupAPI.isStopped(null))
    }

    @Test
    fun `refuses a restore over anything that is running or about to`() {
        listOf(
            ServerProcessState.STARTING,
            ServerProcessState.RUNNING,
            ServerProcessState.STOPPING,
            ServerProcessState.INSTALLING
        ).forEach { state ->
            assertFalse(PanelRestoreServerBackupAPI.isStopped(state), state.name)
        }
    }
}
