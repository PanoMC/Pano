package com.panomc.platform.archive.instance

import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * An archive whose table prefix is empty (or just short) does not get to replace or drop tables in the target's
 * database that are not the target's own. Gated like the other DB tests: `PANO_IT_MARIADB=host:port`,
 * `PANO_IT_MARIADB_PASSWORD`, `PANO_IT_MARIADB_DATABASE` (the target; the source is `<that>_src`, made and dropped here).
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class InstanceRestorePrefixMariaDbIT {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:3306"
    private val password = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "pano"
    private val target = System.getenv("PANO_IT_MARIADB_DATABASE") ?: "pano_it_dao"
    private val source = target + "_src"

    private suspend fun <T> connect(database: String, block: suspend (SqlConnection) -> T): T {
        val connection = MySQLConnection.connect(
            vertx,
            MySQLConnectOptions().setHost(address.substringBefore(':')).setPort(address.substringAfter(':').toInt())
                .setUser("root").setPassword(password).setDatabase(database)
        ).coAwait()

        try {
            return block(connection)
        } finally {
            connection.close().coAwait()
        }
    }

    private suspend fun SqlConnection.run(vararg sql: String) = sql.forEach { query(it).execute().coAwait() }

    private suspend fun SqlConnection.tables(): List<String> =
        query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")
            .execute().coAwait().map { it.getString(0) }

    private fun layout(root: File) = InstanceLayout(
        configFile = File(root, "config.conf"), pluginsDir = File(root, "plugins"), themesDir = File(root, "themes"),
        uploadsDir = File(root, "file-uploads"), maintenanceDir = File(root, "maintenance"), tempDir = File(root, ".temp")
    )

    /** An archive of a Pano that uses no table prefix, its database holding [tables]. */
    private suspend fun emptyPrefixArchive(vararg tables: String): ByteArray {
        connect("mysql") { it.run("DROP DATABASE IF EXISTS `$source`", "CREATE DATABASE `$source` CHARACTER SET utf8mb4") }
        connect(source) { connection ->
            tables.forEach { connection.run("CREATE TABLE `$it` (`id` int) ENGINE=InnoDB", "INSERT INTO `$it` VALUES (7)") }
        }

        val root = File(temp, "source")
        val instance = layout(root)

        instance.configFile.apply { parentFile.mkdirs() }.writeText(
            InstanceArchiverTest.CONFIG.replace("prefix = \"pano_\"", "prefix = \"\"")
        )

        val bytes = ByteArrayOutputStream()

        connect(source) { connection ->
            InstanceArchiver(instance, "", "1.0.0-alpha.520", ArchiveSource(instanceName = "IT")).archive(bytes, null, connection)
        }

        return bytes.toByteArray()
    }

    private fun restorer(): InstanceRestorer {
        val config = JsonObject()
            .put("config-version", 30)
            .put("database", JsonObject().put("host", address).put("name", target).put("username", "root").put("password", password).put("prefix", "pano_"))
            .put("file-uploads-folder", "file-uploads")

        return InstanceRestorer(layout(File(temp, "target")), config, mapOf("core" to 1000), 1000)
    }

    private suspend fun resetTarget(vararg sql: String) = connect(target) { connection ->
        connection.tables().forEach { connection.run("DROP TABLE IF EXISTS `$it`") }
        connection.run(*sql)
    }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> {
        connect("mysql") { it.run("DROP DATABASE IF EXISTS `$source`") }
        resetTarget()
        vertx.close().coAwait()
    }

    @Test
    fun `an empty-prefix archive cannot replace another app's table`() = runBlocking<Unit> {
        val archive = emptyPrefixArchive("shop_orders")

        resetTarget(
            "CREATE TABLE `shop_orders` (`id` int) ENGINE=InnoDB", "INSERT INTO `shop_orders` VALUES (1)",
            "CREATE TABLE `pano_user` (`id` int) ENGINE=InnoDB"
        )

        val failure = assertThrows<PanoArcException> {
            runBlocking { connect(target) { restorer().restore(archive.inputStream(), PanoArcKeys.NONE, it) { } } }
        }

        assertEquals(PanoArcException.Code.UNSAFE_SQL, failure.code)

        connect(target) { connection ->
            assertEquals(listOf("pano_user", "shop_orders"), connection.tables())
            assertEquals(1, connection.query("SELECT `id` FROM `shop_orders`").execute().coAwait().first().getInteger(0))
        }
    }

    @Test
    fun `an empty-prefix archive drops only the target's tables and its own`() = runBlocking<Unit> {
        val archive = emptyPrefixArchive("site_posts")

        resetTarget(
            "CREATE TABLE `forum_users` (`id` int) ENGINE=InnoDB", "INSERT INTO `forum_users` VALUES (3)",
            "CREATE TABLE `pano_user` (`id` int) ENGINE=InnoDB"
        )

        val result = connect(target) { restorer().restore(archive.inputStream(), PanoArcKeys.NONE, it) { } }

        assertEquals(listOf("pano_user"), result.droppedTables)

        connect(target) { connection ->
            assertEquals(listOf("forum_users", "site_posts"), connection.tables())
            assertEquals(3, connection.query("SELECT `id` FROM `forum_users`").execute().coAwait().first().getInteger(0))
        }
    }
}
