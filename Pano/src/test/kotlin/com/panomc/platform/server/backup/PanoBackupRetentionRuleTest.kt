package com.panomc.platform.server.backup

import com.panomc.platform.server.backup.PanoBackupRetentionRule.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PanoBackupRetentionRuleTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    /** Five backups, one a day, the newest today. */
    private val rows = (0 until 5).map { Row("b$it", now - it * day) }

    private fun select(settings: ServerPanoBackupSettings, keep: String? = null) =
        PanoBackupRetentionRule.selectForDeletion(rows.shuffled(), settings, now, keep).map { it.id }

    @Test
    fun `NONE keeps everything`() {
        assertEquals(emptyList<String>(), select(ServerPanoBackupSettings(retention = PanoBackupRetention.NONE)))
    }

    @Test
    fun `COUNT keeps the newest N, and a limit below one reads as the default`() {
        assertEquals(listOf("b2", "b3", "b4"), select(ServerPanoBackupSettings(retention = PanoBackupRetention.COUNT, keepLast = 2)))
        assertEquals(emptyList<String>(), select(ServerPanoBackupSettings(retention = PanoBackupRetention.COUNT, keepLast = 0)))
    }

    @Test
    fun `DAYS keeps the last X days and always the newest one`() {
        assertEquals(listOf("b3", "b4"), select(ServerPanoBackupSettings(retention = PanoBackupRetention.DAYS, keepDays = 2)))

        val stale = listOf(Row("old1", now - 40 * day), Row("old2", now - 50 * day))

        assertEquals(
            listOf("old2"),
            PanoBackupRetentionRule.selectForDeletion(stale, ServerPanoBackupSettings(retention = PanoBackupRetention.DAYS, keepDays = 30), now, null).map { it.id }
        )
    }

    @Test
    fun `the backup just uploaded is never chosen`() {
        assertEquals(listOf("b2", "b3"), select(ServerPanoBackupSettings(retention = PanoBackupRetention.COUNT, keepLast = 2), keep = "b4"))
    }
}
