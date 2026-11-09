package com.panomc.platform.route.settings

import com.panomc.platform.route.api.panel.settings.LuckPermsH2Guard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class LuckPermsH2GuardTest {
    @TempDir
    lateinit var dir: File

    private fun database(name: String, vararg sql: String): File {
        Class.forName("org.h2.Driver")
        DriverManager.getConnection("jdbc:h2:${File(dir, name).absolutePath};NON_KEYWORDS=VALUE", "", "").use { conn ->
            conn.createStatement().use { st -> sql.forEach { st.execute(it) } }
        }
        return File(dir, "$name.mv.db")
    }

    private val luckPermsSchema = arrayOf(
        "CREATE TABLE luckperms_group_permissions(id INT AUTO_INCREMENT NOT NULL, name VARCHAR(36) NOT NULL, " +
                "permission VARCHAR(200) NOT NULL, value BOOL NOT NULL, server VARCHAR(36) NOT NULL, world VARCHAR(64) NOT NULL, " +
                "expiry BIGINT NOT NULL, contexts VARCHAR(200) NOT NULL, PRIMARY KEY (id))",
        "CREATE INDEX luckperms_group_permissions_name ON luckperms_group_permissions (name)",
        "INSERT INTO luckperms_group_permissions(name, permission, value, server, world, expiry, contexts) " +
                "VALUES('default', 'essentials.alias.trigger', TRUE, 'global', 'global', 0, '{}')"
    )

    @Test
    fun `a plain LuckPerms database passes, even with trigger-like words in its data`() {
        val file = database("lp", *luckPermsSchema)
        assertDoesNotThrow { LuckPermsH2Guard.requireSafe(file) }
    }

    @Test
    fun `a database with an alias is refused`() {
        val file = database("alias", *luckPermsSchema, "CREATE ALIAS GET_PROP FOR 'java.lang.System.getProperty(java.lang.String)'")
        assertThrows<IllegalArgumentException> { LuckPermsH2Guard.requireSafe(file) }
    }

    @Test
    fun `a database with a trigger is refused`() {
        val file = database(
            "trigger", *luckPermsSchema,
            "CREATE TRIGGER T BEFORE INSERT ON luckperms_group_permissions AS " +
                "'org.h2.api.Trigger create() { return new org.h2.api.Trigger() { " +
                "public void fire(java.sql.Connection c, Object[] o, Object[] n) {} }; }'"
        )
        assertThrows<IllegalArgumentException> { LuckPermsH2Guard.requireSafe(file) }
    }

    @Test
    fun `a file that is not a database is refused`() {
        val file = File(dir, "junk.mv.db").apply { writeText("not a database") }
        assertThrows<IllegalArgumentException> { LuckPermsH2Guard.requireSafe(file) }
    }

    @Test
    fun `a hidden statement on a continuation line is still caught`() {
        val script = "---- Schema ----\nCREATE CACHED TABLE \"T\"(\n    \"A\" INT\n);\n  CREATE FORCE TRIGGER X AS 'x';\n"
        assertTrue(LuckPermsH2Guard.unsafeStatements(script).isNotEmpty())
    }
}
