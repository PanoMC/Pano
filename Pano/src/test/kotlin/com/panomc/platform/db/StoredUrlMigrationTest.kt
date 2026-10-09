package com.panomc.platform.db

import com.panomc.platform.db.migration.DatabaseMigration58to59
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * The stored-URL migration (doc 04 section 9): the rewrite table is a subset of the core rename table, the Kotlin
 * reference rewrite does what is documented, and the SQL gives the same answer as the reference on a real MariaDB
 * (that part runs only with `PANO_IT_MARIADB=host:port` and `PANO_IT_MARIADB_PASSWORD` set, on a scratch database
 * it creates and drops).
 */
class StoredUrlMigrationTest {
    @Test
    fun `migrates 58 to 59 with one handler`() {
        val migration = DatabaseMigration58to59()

        assertEquals(58, migration.from)
        assertEquals(59, migration.to)
        assertEquals(1, migration.handlers.size)
        assertTrue(migration.isMigratable(58))
        assertFalse(migration.isMigratable(57))
    }

    @Test
    fun `every rewritten prefix comes from the core rename table`() {
        val table = renameTable()

        for ((old, new) in DatabaseMigration58to59.RENAMES) {
            val rows = table.filter { it.getString("old").startsWith(old) && it.getString("method") == "GET" }

            assertTrue(rows.isNotEmpty(), "no GET route of the rename table starts with $old")
            assertTrue(
                rows.all { it.getString("new").startsWith(new) },
                "$old is renamed to $new, the table says " + rows.map { it.getString("new") }
            )
        }
    }

    @Test
    fun `the new prefixes are mounted routes and contain no old prefix`() {
        for ((old, new) in DatabaseMigration58to59.RENAMES) {
            assertTrue(new.startsWith("/api/v1/"), new)
            assertFalse(new.contains(old), "$new still contains $old, a second run would rewrite it again")
        }
    }

    @Test
    fun `rewrite handles bodies, thumbnails and settings`() {
        assertEquals(
            "/api/v1/posts/thumbnails/a1.png",
            DatabaseMigration58to59.rewrite("/api/post/thumbnail/a1.png")
        )
        assertEquals(
            """<p><img src="/api/v1/posts/thumbnails/a.png"> and <img src="https://x.test/api/v1/posts/thumbnails/b.jpg"></p>""",
            DatabaseMigration58to59.rewrite(
                """<p><img src="/api/post/thumbnail/a.png"> and <img src="https://x.test/api/post/thumbnail/b.jpg"></p>"""
            )
        )
        assertEquals(
            """{"blaze-theme":{"hero":"/api/v1/theme/file/hero.webp","logo":"/api/v1/website-logo?hash=ab12"}}""",
            DatabaseMigration58to59.rewrite(
                """{"blaze-theme":{"hero":"/api/theme/file/hero.webp","logo":"/api/websiteLogo?hash=ab12"}}"""
            )
        )
        assertEquals(
            """{"favicon":"/api/v1/server/icon/default","avatar":"/api/v1/profile/picture/steve"}""",
            DatabaseMigration58to59.rewrite("""{"favicon":"/api/server/icon/default","avatar":"/api/profile/picture/steve"}""")
        )
    }

    @Test
    fun `rewrite is idempotent and leaves everything else alone`() {
        val text = "see /api/v1/posts/thumbnails/a.png, /api/posts (not a file route), https://example.com/docs/api/ and plain text"

        assertEquals(text, DatabaseMigration58to59.rewrite(text))

        val once = DatabaseMigration58to59.rewrite("/api/post/thumbnail/a.png /api/favicon")

        assertEquals(once, DatabaseMigration58to59.rewrite(once))
        assertEquals("", DatabaseMigration58to59.rewrite(""))
    }

    @Test
    fun `statements update the four places with the table prefix and only rows that hold an api url`() {
        val statements = DatabaseMigration58to59.statements("pano_")

        assertEquals(4, statements.size)
        assertTrue(statements[0].startsWith("UPDATE `pano_post` SET `text` = REPLACE("), statements[0])
        assertTrue(statements[0].contains("`thumbnailUrl` = REPLACE("), statements[0])
        assertTrue(statements[1].startsWith("UPDATE `pano_notification` SET `details` = "), statements[1])
        assertTrue(statements[2].startsWith("UPDATE `pano_panel_notification` SET `details` = "), statements[2])
        assertTrue(statements[3].startsWith("UPDATE `pano_system_property` SET `value` = "), statements[3])
        assertTrue(statements[3].contains("WHERE `option` = 'theme_settings' AND (`value` LIKE '%/api/%')"), statements[3])

        for (statement in statements) {
            assertTrue(statement.contains("LIKE '%/api/%'"), statement)

            for ((old, new) in DatabaseMigration58to59.RENAMES) {
                assertTrue(statement.contains("'$old', '$new'"), "$old missing in $statement")
            }
        }

        assertTrue(DatabaseMigration58to59.statements("").first().startsWith("UPDATE `post` SET"))
    }

    @Test
    fun `the sql gives the answer of the reference rewrite on a real database`() {
        val address = System.getenv("PANO_IT_MARIADB").orEmpty()
        val password = System.getenv("PANO_IT_MARIADB_PASSWORD").orEmpty()

        assumeTrue(address.isNotBlank() && password.isNotBlank(), "PANO_IT_MARIADB / PANO_IT_MARIADB_PASSWORD not set")

        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "3306").toInt()
        val database = "pano_pf31_" + UUID.randomUUID().toString().replace("-", "").take(10)
        val vertx = Vertx.vertx()

        fun options(name: String?) = MySQLConnectOptions()
            .setHost(host).setPort(port).setUser("root").setPassword(password).setCharset("utf8mb4")
            .apply { if (name != null) setDatabase(name) }

        try {
            runBlocking {
                val admin = MySQLConnection.connect(vertx, options(null)).coAwait()

                try {
                    admin.query("CREATE DATABASE `$database` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci").execute().coAwait()
                } finally {
                    admin.close().coAwait()
                }

                val connection = MySQLConnection.connect(vertx, options(database)).coAwait()

                try {
                    assertSqlMatchesReference(connection)
                } finally {
                    connection.close().coAwait()
                }
            }
        } finally {
            runBlocking {
                val admin = MySQLConnection.connect(vertx, options(null)).coAwait()

                try {
                    admin.query("DROP DATABASE IF EXISTS `$database`").execute().coAwait()
                } finally {
                    admin.close().coAwait()
                }
            }

            runBlocking { vertx.close().coAwait() }
        }
    }

    private suspend fun assertSqlMatchesReference(connection: SqlConnection) {
        val prefix = "pano_"
        val thumb = "/api/post/thumbnail/t1.png"
        val body = """<p><img src="/api/post/thumbnail/b1.png"/> <a href="/api/posts">x</a> /api/v1/theme/file/keep.png</p>"""
        val notification = """{"favicon":"/api/server/icon/default","serverId":4}"""
        val panelNotification = """{"icon":"/api/panel/updates/icon/u.png"}"""
        val settings = """{"blaze-theme":{"hero":"/api/theme/file/h.webp"}}"""
        val otherProperty = """{"note":"/api/theme/file/stays.webp"}"""

        connection.query("CREATE TABLE `${prefix}post` (`id` bigint PRIMARY KEY AUTO_INCREMENT, `text` LONGTEXT NOT NULL, `thumbnailUrl` MEDIUMTEXT NOT NULL)").execute().coAwait()
        connection.query("CREATE TABLE `${prefix}notification` (`id` bigint PRIMARY KEY AUTO_INCREMENT, `details` MEDIUMTEXT NOT NULL)").execute().coAwait()
        connection.query("CREATE TABLE `${prefix}panel_notification` (`id` bigint PRIMARY KEY AUTO_INCREMENT, `details` MEDIUMTEXT NOT NULL)").execute().coAwait()
        connection.query("CREATE TABLE `${prefix}system_property` (`id` bigint PRIMARY KEY AUTO_INCREMENT, `option` TEXT NOT NULL, `value` TEXT NOT NULL)").execute().coAwait()

        fun quote(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"

        connection.query("INSERT INTO `${prefix}post` (`text`, `thumbnailUrl`) VALUES (${quote(body)}, ${quote(thumb)}), ('plain', '')").execute().coAwait()
        connection.query("INSERT INTO `${prefix}notification` (`details`) VALUES (${quote(notification)})").execute().coAwait()
        connection.query("INSERT INTO `${prefix}panel_notification` (`details`) VALUES (${quote(panelNotification)})").execute().coAwait()
        connection.query(
            "INSERT INTO `${prefix}system_property` (`option`, `value`) VALUES ('theme_settings', ${quote(settings)}), ('other', ${quote(otherProperty)})"
        ).execute().coAwait()

        // Twice: the second run must change nothing.
        repeat(2) {
            DatabaseMigration58to59.statements(prefix).forEach { connection.query(it).execute().coAwait() }
        }

        suspend fun single(sql: String): String = connection.query(sql).execute().coAwait().first().getString(0)

        assertEquals(DatabaseMigration58to59.rewrite(body), single("SELECT `text` FROM `${prefix}post` WHERE `id` = 1"))
        assertEquals(DatabaseMigration58to59.rewrite(thumb), single("SELECT `thumbnailUrl` FROM `${prefix}post` WHERE `id` = 1"))
        assertEquals("plain", single("SELECT `text` FROM `${prefix}post` WHERE `id` = 2"))
        assertEquals(DatabaseMigration58to59.rewrite(notification), single("SELECT `details` FROM `${prefix}notification`"))
        assertEquals(DatabaseMigration58to59.rewrite(panelNotification), single("SELECT `details` FROM `${prefix}panel_notification`"))
        assertEquals(DatabaseMigration58to59.rewrite(settings), single("SELECT `value` FROM `${prefix}system_property` WHERE `option` = 'theme_settings'"))
        assertEquals(otherProperty, single("SELECT `value` FROM `${prefix}system_property` WHERE `option` = 'other'"))
        assertTrue(single("SELECT `text` FROM `${prefix}post` WHERE `id` = 1").contains("/api/posts"))
    }

    /** `Pano/api/paths.old-new.json`, found from the working directory of the test (`Pano/` or the repository root). */
    private fun renameTable(): List<JsonObject> {
        var dir: File? = File("").absoluteFile

        while (dir != null) {
            for (candidate in listOf("Pano/api/paths.old-new.json", "api/paths.old-new.json")) {
                val file = File(dir, candidate)

                if (file.isFile) {
                    return JsonArray(file.readText()).map { it as JsonObject }
                }
            }

            dir = dir.parentFile
        }

        throw AssertionError("paths.old-new.json not found above ${File("").absolutePath}")
    }
}
