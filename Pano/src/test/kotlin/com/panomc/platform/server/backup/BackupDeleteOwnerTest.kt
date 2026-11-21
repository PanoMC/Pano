package com.panomc.platform.server.backup

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which side may delete a backup's files when retention or a person removes its row. */
class BackupDeleteOwnerTest {
    @Test
    fun `a node deletes its own backups and the plugin's backups of its server`() {
        assertTrue(ManagedServerBackupService.ownsArchive(targetNodeId = 4, backupNodeId = 4, serverNodeId = 4))
        // The plugin took it into the server folder, which is on node 4.
        assertTrue(ManagedServerBackupService.ownsArchive(targetNodeId = 4, backupNodeId = null, serverNodeId = 4))
        // The plugin deletes its own.
        assertTrue(ManagedServerBackupService.ownsArchive(targetNodeId = null, backupNodeId = null, serverNodeId = 4))
    }

    @Test
    fun `nobody deletes a backup kept somewhere else`() {
        assertFalse(ManagedServerBackupService.ownsArchive(targetNodeId = 5, backupNodeId = 4, serverNodeId = 5))
        assertFalse(ManagedServerBackupService.ownsArchive(targetNodeId = null, backupNodeId = 4, serverNodeId = 4))
        assertFalse(ManagedServerBackupService.ownsArchive(targetNodeId = 5, backupNodeId = null, serverNodeId = 4))
    }
}
