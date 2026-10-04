package com.panomc.platform.server.backup

import com.panomc.platform.db.model.ServerBackup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupRetentionTest {
    private fun backup(
        id: Long,
        createdAt: Long,
        status: ServerBackupStatus = ServerBackupStatus.READY,
        mode: BackupMode = BackupMode.FULL,
        pinned: Boolean = false,
        storedBytes: Long? = null
    ) = ServerBackup(
        id = id,
        uuid = "uuid-$id",
        serverId = 33,
        nodeId = 1,
        name = "backup-$id",
        sizeBytes = 100,
        sha256 = "hash",
        status = status,
        createdBy = 1,
        createdAt = createdAt,
        mode = mode,
        pinned = pinned,
        storedBytes = storedBytes
    )

    private fun snapshot(
        id: Long,
        createdAt: Long,
        storedBytes: Long? = 100,
        pinned: Boolean = false,
        status: ServerBackupStatus = ServerBackupStatus.READY
    ) = backup(id, createdAt, status, BackupMode.SNAPSHOT, pinned, storedBytes)

    private fun select(
        backups: List<ServerBackup>,
        keepLast: Int = 10,
        snapshotKeepLast: Int = 24,
        snapshotMaxBytes: Long? = null
    ) = BackupRetention.selectForDeletion(backups, keepLast, snapshotKeepLast, snapshotMaxBytes).map { it.id }

    @Test
    fun `keeps nothing back while under the limit`() {
        val backups = listOf(backup(1, 100), backup(2, 200))

        assertTrue(BackupRetention.selectForDeletion(backups, 10).isEmpty())
    }

    @Test
    fun `drops the oldest once over the limit`() {
        val backups = listOf(backup(1, 100), backup(2, 200), backup(3, 300), backup(4, 400))

        val surplus = BackupRetention.selectForDeletion(backups, 2)

        assertEquals(listOf(1L, 2L), surplus.map { it.id })
    }

    @Test
    fun `ignores the order it was handed`() {
        val backups = listOf(backup(3, 300), backup(1, 100), backup(4, 400), backup(2, 200))

        val surplus = BackupRetention.selectForDeletion(backups, 2)

        assertEquals(listOf(1L, 2L), surplus.map { it.id })
    }

    @Test
    fun `does not count a backup that is still running towards the limit`() {
        val backups = listOf(
            backup(1, 100),
            backup(2, 200),
            backup(3, 300, ServerBackupStatus.CREATING)
        )

        assertTrue(BackupRetention.selectForDeletion(backups, 2).isEmpty())
    }

    @Test
    fun `removes a failed backup without spending a slot on it`() {
        val backups = listOf(
            backup(1, 100, ServerBackupStatus.FAILED),
            backup(2, 200),
            backup(3, 300)
        )

        val surplus = BackupRetention.selectForDeletion(backups, 2)

        assertEquals(listOf(1L), surplus.map { it.id })
    }

    @Test
    fun `reads a missing or nonsensical limit as the default, never as delete everything`() {
        val backups = (1..12).map { backup(it.toLong(), it * 100L) }

        assertEquals(2, BackupRetention.selectForDeletion(backups, 0).size)
        assertEquals(2, BackupRetention.selectForDeletion(backups, -5).size)
    }

    @Test
    fun `hands the surplus back oldest first`() {
        val backups = (1..6).map { backup(it.toLong(), it * 100L) }

        val surplus = BackupRetention.selectForDeletion(backups, 2)

        assertEquals(listOf(1L, 2L, 3L, 4L), surplus.map { it.id })
    }

    @Test
    fun `never selects a pinned backup, and a pin does not spend a slot`() {
        val backups = listOf(
            backup(1, 100, pinned = true),
            backup(2, 200),
            backup(3, 300),
            backup(4, 400)
        )

        // Two unpinned slots: 3 and 4 stay, 2 goes, and the pinned 1 — the oldest — survives.
        assertEquals(listOf(2L), select(backups, keepLast = 2))
    }

    @Test
    fun `never selects a pinned failed backup`() {
        val backups = listOf(backup(1, 100, ServerBackupStatus.FAILED, pinned = true), backup(2, 200))

        assertTrue(select(backups).isEmpty())
    }

    @Test
    fun `counts full zips and snapshots apart`() {
        val backups = listOf(
            backup(1, 100),
            snapshot(2, 200),
            snapshot(3, 300),
            snapshot(4, 400),
            backup(5, 500)
        )

        // Two full zips under a limit of two stay; three snapshots over a limit of one lose two.
        assertEquals(listOf(2L, 3L), select(backups, keepLast = 2, snapshotKeepLast = 1))

        // And the other way round: snapshots never push out a full zip.
        assertEquals(listOf(1L), select(backups, keepLast = 1, snapshotKeepLast = 10))
    }

    @Test
    fun `reads a nonsensical snapshot limit as the default`() {
        val backups = (1..30).map { snapshot(it.toLong(), it * 100L) }

        assertEquals(6, select(backups, snapshotKeepLast = 0).size)
        assertEquals(6, select(backups, snapshotKeepLast = -3).size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), select(backups, snapshotKeepLast = 0))
    }

    @Test
    fun `does not count a running or failed snapshot towards the limit`() {
        val backups = listOf(
            snapshot(1, 100),
            snapshot(2, 200, status = ServerBackupStatus.CREATING),
            snapshot(3, 300, status = ServerBackupStatus.FAILED),
            snapshot(4, 400)
        )

        assertEquals(listOf(3L), select(backups, snapshotKeepLast = 2))
    }

    @Test
    fun `drops the oldest snapshots until the rest fit under the disk cap`() {
        val backups = (1..5).map { snapshot(it.toLong(), it * 100L, storedBytes = 100) }

        // 500 bytes against a cap of 250: 1, 2 and 3 go, leaving 200.
        assertEquals(listOf(1L, 2L, 3L), select(backups, snapshotMaxBytes = 250))
    }

    @Test
    fun `leaves everything alone when the snapshots already fit`() {
        val backups = (1..5).map { snapshot(it.toLong(), it * 100L, storedBytes = 100) }

        assertTrue(select(backups, snapshotMaxBytes = 500).isEmpty())
        assertTrue(select(backups, snapshotMaxBytes = 10_000).isEmpty())
    }

    @Test
    fun `reads a zero or missing cap as no cap`() {
        val backups = (1..5).map { snapshot(it.toLong(), it * 100L, storedBytes = 1_000_000) }

        assertTrue(select(backups, snapshotMaxBytes = 0).isEmpty())
        assertTrue(select(backups, snapshotMaxBytes = null).isEmpty())
        assertTrue(select(backups, snapshotMaxBytes = -1).isEmpty())
    }

    @Test
    fun `never removes the newest snapshot for the cap, even when it alone is over`() {
        val backups = listOf(
            snapshot(1, 100, storedBytes = 10),
            snapshot(2, 200, storedBytes = 10),
            snapshot(3, 300, storedBytes = 1_000)
        )

        assertEquals(listOf(1L, 2L), select(backups, snapshotMaxBytes = 50))
    }

    @Test
    fun `counts pinned snapshots towards the cap but never removes them`() {
        val backups = listOf(
            snapshot(1, 100, storedBytes = 300, pinned = true),
            snapshot(2, 200, storedBytes = 100),
            snapshot(3, 300, storedBytes = 100),
            snapshot(4, 400, storedBytes = 100)
        )

        // 600 bytes against 450: the pinned 300 stays, so 2 and then 3 go to get down to 400.
        assertEquals(listOf(2L, 3L), select(backups, snapshotMaxBytes = 450))
    }

    @Test
    fun `stops at the newest when pinned snapshots alone exceed the cap`() {
        val backups = listOf(
            snapshot(1, 100, storedBytes = 1_000, pinned = true),
            snapshot(2, 200, storedBytes = 100),
            snapshot(3, 300, storedBytes = 100)
        )

        assertEquals(listOf(2L), select(backups, snapshotMaxBytes = 500))
    }

    @Test
    fun `applies the cap to what the count left, without counting a snapshot twice`() {
        val backups = (1..6).map { snapshot(it.toLong(), it * 100L, storedBytes = 100) }

        // The count keeps 4..6 (300 bytes); the cap of 150 then takes 4 and 5, never 6.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), select(backups, snapshotKeepLast = 3, snapshotMaxBytes = 150))
    }

    @Test
    fun `ignores full zips and unfinished snapshots when adding up the cap`() {
        val backups = listOf(
            backup(1, 100, storedBytes = 1_000_000),
            snapshot(2, 200, storedBytes = 100),
            snapshot(3, 300, storedBytes = 5_000, status = ServerBackupStatus.CREATING),
            snapshot(4, 400, storedBytes = 100)
        )

        assertTrue(select(backups, snapshotMaxBytes = 200).isEmpty())
    }

    @Test
    fun `neither counts nor removes a snapshot with no stored size for the cap`() {
        val backups = listOf(
            snapshot(1, 100, storedBytes = null),
            snapshot(2, 200, storedBytes = 300),
            snapshot(3, 300, storedBytes = null)
        )

        assertEquals(listOf(2L), select(backups, snapshotMaxBytes = 100))
    }

    @Test
    fun `hands back a mixed surplus oldest first without duplicates`() {
        val backups = listOf(
            snapshot(1, 100, storedBytes = 100),
            backup(2, 150),
            snapshot(3, 200, status = ServerBackupStatus.FAILED),
            snapshot(4, 300, storedBytes = 100),
            backup(5, 350),
            snapshot(6, 400, storedBytes = 100)
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), select(backups, keepLast = 1, snapshotKeepLast = 2, snapshotMaxBytes = 100))
    }
}
