package com.panomc.platform.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A failing scheduled local backup waits before the next try, longer each time, at most a day. */
class PanoBackupScheduleBackoffTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun `the wait doubles from one hour up to a day`() {
        assertEquals(0L, PanoBackupManager.scheduledBackoff(0))
        assertEquals(hour, PanoBackupManager.scheduledBackoff(1))
        assertEquals(2 * hour, PanoBackupManager.scheduledBackoff(2))
        assertEquals(16 * hour, PanoBackupManager.scheduledBackoff(5))
        assertEquals(24 * hour, PanoBackupManager.scheduledBackoff(6))
        assertEquals(24 * hour, PanoBackupManager.scheduledBackoff(1000))
    }
}
