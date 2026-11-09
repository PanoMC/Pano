package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.error.InvalidData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MigrationSqlNamesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `plain prefixes and table names pass`() {
        assertEquals("luckperms_", MigrationSqlNames.tablePrefix("luckperms_"))
        assertEquals("", MigrationSqlNames.tablePrefix(""))
        assertEquals("authme", MigrationSqlNames.tableName("authme"))
    }

    @Test
    fun `names that would change the SQL are refused`() {
        assertThrows<InvalidData> { MigrationSqlNames.tablePrefix("x` WHERE FILE_WRITE(X'00', 'plugins/x.jar') > 0 -- ") }
        assertThrows<InvalidData> { MigrationSqlNames.tablePrefix("a b") }
        assertThrows<InvalidData> { MigrationSqlNames.tableName("authme`; DROP TABLE x; --") }
        assertThrows<InvalidData> { MigrationSqlNames.tableName("") }
    }

    @Test
    fun `an H2 file with stored code is refused unopened`() {
        val plain = File(dir, "plain.mv.db").apply { writeText("H:2,block:...CREATE CACHED TABLE \"PUBLIC\".\"LUCKPERMS_GROUPS\"(...)") }
        val trigger = File(dir, "trigger.mv.db").apply { writeText("...create trigger t before select on x as \$\$ ... \$\$") }
        val view = File(dir, "view.mv.db").apply { writeText("CREATE FORCE VIEW \"PUBLIC\".\"LUCKPERMS_GROUPS\" AS SELECT FILE_WRITE(...)") }
        val utf16 = File(dir, "utf16.mv.db").apply { writeBytes("CREATE ALIAS X".toByteArray(Charsets.UTF_16LE)) }

        assertTrue(MigrationSqlNames.isPlainH2Export(plain))
        assertFalse(MigrationSqlNames.isPlainH2Export(trigger))
        assertFalse(MigrationSqlNames.isPlainH2Export(view))
        assertFalse(MigrationSqlNames.isPlainH2Export(utf16))
    }
}
