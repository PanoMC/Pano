package com.panomc.platform.backup

import com.panomc.platform.archive.ArchiveLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A restore unpacks at most a multiple of the archive's size, and never more than the staging disk can hold. */
class PanoBackupRestoreLimitsTest {
    private val gib = 1L shl 30
    private val base = ArchiveLimits()

    @Test
    fun `a small archive gets the floor, a big one its ratio`() {
        assertEquals(gib, PanoBackupService.restoreLimits(base, 10L shl 20, 500 * gib).maxTotalBytes)
        assertEquals(50 * gib, PanoBackupService.restoreLimits(base, gib, 500 * gib).maxTotalBytes)
        assertEquals(base.maxTotalBytes, PanoBackupService.restoreLimits(base, 10 * gib, 500 * gib).maxTotalBytes)
    }

    @Test
    fun `free space caps it, minus a reserve`() {
        val limits = PanoBackupService.restoreLimits(base, gib, 3 * gib)

        assertEquals(3 * gib - PanoBackupService.RESTORE_FREE_SPACE_RESERVE, limits.maxTotalBytes)
        assertEquals(limits.maxTotalBytes, limits.maxEntryBytes)
        assertEquals(0L, PanoBackupService.restoreLimits(base, gib, 100).maxTotalBytes)
    }
}
