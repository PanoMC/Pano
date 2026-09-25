package com.panomc.platform.db

import com.panomc.platform.db.migration.DatabaseMigration53to54
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerMetricRssMigrationTest {
    @Test
    fun `migrates 53 to 54 with the process memory column`() {
        val migration = DatabaseMigration53to54()

        assertEquals(53, migration.from)
        assertEquals(54, migration.to)
        assertEquals(1, migration.handlers.size)
        assertTrue(migration.isMigratable(53))
        assertFalse(migration.isMigratable(52))
    }
}
