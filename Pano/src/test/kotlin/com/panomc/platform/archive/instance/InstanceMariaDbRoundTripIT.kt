package com.panomc.platform.archive.instance

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.db.PanoNativeDumper
import com.panomc.platform.archive.db.SanitisedSqlImporter
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Real MariaDB round trip: dump → archive (passphrase) → restore into another database, compared row
 * by row. Gated: `PANO_IT_MARIADB=host:port` (+ `PANO_IT_MARIADB_PASSWORD`, root), e.g. Docker
 * `ph-w6-mariadb` on 127.0.0.1:18306.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class InstanceMariaDbRoundTripIT {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:18306"
    private val password = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "w6root"
    private val pools = mutableListOf<Pool>()

    private fun pool(database: String): Pool = MySQLBuilder.pool()
        .connectingTo(
            MySQLConnectOptions().setHost(address.substringBefore(':')).setPort(address.substringAfter(':').toInt())
                .setUser("root").setPassword(password).setDatabase(database)
        )
        .with(PoolOptions().setMaxSize(2))
        .using(vertx)
        .build()
        .also { pools.add(it) }

    private suspend fun <T> withConnection(database: String, block: suspend (SqlConnection) -> T): T {
        val connection = pool(database).connection.coAwait()

        try {
            return block(connection)
        } finally {
            connection.close().coAwait()
        }
    }

    private suspend fun SqlConnection.run(vararg sql: String) = sql.forEach { query(it).execute().coAwait() }

    @BeforeAll
    fun createDatabases() = runBlocking<Unit> { recreateDatabases() }

    private suspend fun recreateDatabases() {
        withConnection("mysql") {
            it.run(
                "DROP DATABASE IF EXISTS ph_w6_src", "DROP DATABASE IF EXISTS ph_w6_dst",
                "CREATE DATABASE ph_w6_src CHARACTER SET utf8mb4", "CREATE DATABASE ph_w6_dst CHARACTER SET utf8mb4"
            )
        }
    }

    @AfterAll
    fun dropDatabases() = runBlocking<Unit> {
        withConnection("mysql") { it.run("DROP DATABASE IF EXISTS ph_w6_src", "DROP DATABASE IF EXISTS ph_w6_dst") }
        pools.forEach { it.close().coAwait() }
        vertx.close().coAwait()
    }

    private val tricky = "it's \\ \"q\" ; -- /* */ \n\r\t NUL\u0000 ctrl-z\u001A emoji \uD83D\uDE00 ğüşİ '); DROP DATABASE x; --"

    private suspend fun seedSource(connection: SqlConnection) {
        connection.run(
            """CREATE TABLE `pano_scheme_version` (`pluginId` varchar(255), `when` timestamp not null default CURRENT_TIMESTAMP,
               `key` varchar(255) not null, `extra` varchar(255), UNIQUE INDEX `pluginId_key_idx` (`pluginId`, `key`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            "INSERT INTO pano_scheme_version (pluginId, `key`, extra) VALUES (NULL, '9', 'Init'), (NULL, '55', 'x'), ('pano-plugin-market', '3', NULL), ('pano-plugin-market', '12', NULL)",
            "CREATE TABLE `pano_group` (`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, `name` varchar(64) NOT NULL) ENGINE=InnoDB",
            """CREATE TABLE `pano_user` (
                `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
                `group_id` bigint NULL,
                `name` varchar(255) NULL,
                `bio` mediumtext NULL,
                `avatar` blob NULL,
                `code` varbinary(16) NULL,
                `flag` tinyint(1) NULL,
                `bits` bit(5) NULL,
                `price` decimal(14,4) NULL,
                `ratio` double NULL,
                `small` float NULL,
                `big` bigint unsigned NULL,
                `created` datetime(6) NULL,
                `ts` timestamp NULL DEFAULT NULL,
                `day` date NULL,
                `clock` time(3) NULL,
                `yr` year NULL,
                `kind` enum('a','b''c') NULL,
                `tags` set('x','y','z') NULL,
                `meta` longtext NULL CHECK (json_valid(`meta`)),
                `doubled` bigint GENERATED ALWAYS AS (`id` * 2) VIRTUAL,
                CONSTRAINT `fk_group` FOREIGN KEY (`group_id`) REFERENCES `pano_group` (`id`) ON DELETE SET NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='users; with -- tricky comment'""",
            "CREATE TABLE `pano_bulk` (`id` int NOT NULL PRIMARY KEY, `v` varchar(40)) ENGINE=InnoDB",
            "CREATE TABLE `other_keep` (`id` int) ENGINE=InnoDB",
            "INSERT INTO other_keep VALUES (1)",
            "INSERT INTO pano_group (name) VALUES ('admins'), ('users')"
        )

        connection.preparedQuery(
            "INSERT INTO pano_user (group_id, name, bio, avatar, code, flag, bits, price, ratio, small, big, created, ts, day, clock, yr, kind, tags, meta) " +
                    "VALUES (?, ?, ?, ?, ?, ?, b'10101', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).execute(
            Tuple.tuple()
                .addLong(1).addString(tricky).addString(tricky.repeat(2000)).addBuffer(Buffer.buffer(ByteArray(256) { it.toByte() }))
                .addBuffer(Buffer.buffer(ByteArray(0))).addInteger(1).addString("-12345678.1234").addDouble(1.0E-300)
                .addFloat(0.1f).addString("18446744073709551615").addString("2026-09-26 12:34:56.789012")
                .addString("2026-03-29 02:30:00").addString("2026-02-28").addString("-838:59:59.000").addInteger(2155)
                .addString("b'c").addString("x,z").addString("{\"a\": [1, \"\\u0000\"]}")
        ).coAwait()

        connection.run(
            "INSERT INTO pano_user (group_id) VALUES (NULL)",
            "INSERT INTO pano_user (id, name) VALUES (1000, 'high id')",
            "INSERT INTO pano_bulk (id, v) SELECT seq, CONCAT('row-', seq, '-', REPEAT('x', seq % 30)) FROM seq_1_to_2500"
        )
    }

    private suspend fun snapshot(connection: SqlConnection): Map<String, List<String>> {
        val tables = connection
            .query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME")
            .execute().coAwait().map { it.getString(0) }

        return tables.associateWith { table ->
            val rows = connection.query("SELECT * FROM `$table`").execute().coAwait()

            rows.map { row ->
                (0 until row.size()).joinToString("|") { index ->
                    when (val value = row.getValue(index)) {
                        null -> "NULL"
                        is Buffer -> "0x" + value.bytes.joinToString("") { "%02x".format(it) }
                        else -> "${value.javaClass.simpleName}:$value"
                    }
                }
            }.sorted()
        }
    }

    private fun instance(root: File) = InstanceLayout(
        configFile = File(root, "config.conf"), pluginsDir = File(root, "plugins"), themesDir = File(root, "themes"),
        uploadsDir = File(root, "file-uploads"), maintenanceDir = File(root, "maintenance"), tempDir = File(root, ".temp")
    )

    @Test
    fun `dump, archive, restore into another database and compare`() = runBlocking<Unit> {
        val sourceRoot = File(temp, "source")
        val source = instance(sourceRoot)

        source.configFile.apply { parentFile.mkdirs() }.writeText(InstanceArchiverTest.CONFIG)
        File(sourceRoot, "plugins").mkdirs()
        File(sourceRoot, "plugins/market.jar").writeBytes(ByteArray(4096) { (it % 251).toByte() })
        File(sourceRoot, "themes/vanilla-theme").mkdirs()
        File(sourceRoot, "themes/vanilla-theme/index.js").writeText("source theme")
        File(sourceRoot, "file-uploads/post").mkdirs()
        File(sourceRoot, "file-uploads/post/a.png").writeBytes(ByteArray(3000) { 3 })

        recreateDatabases()

        val sourceSnapshot = withConnection("ph_w6_src") { connection ->
            seedSource(connection)
            snapshot(connection)
        }

        // Target: stale prefix tables and a foreign table that must survive, plus existing files.
        val targetRoot = File(temp, "target")
        val target = instance(targetRoot)

        withConnection("ph_w6_dst") {
            it.run(
                "CREATE TABLE `pano_stale` (`id` int) ENGINE=InnoDB", "CREATE TABLE `pano_user` (`x` int) ENGINE=InnoDB",
                "CREATE TABLE `foreign_keep` (`id` int) ENGINE=InnoDB", "INSERT INTO foreign_keep VALUES (42)"
            )
        }

        File(targetRoot, "plugins").mkdirs()
        File(targetRoot, "plugins/old.jar").writeText("old")
        File(targetRoot, "themes/vanilla-theme").mkdirs()
        File(targetRoot, "themes/vanilla-theme/.pano-license.jwt").writeText("target-license")
        File(targetRoot, "themes/vanilla-theme/index.js").writeText("target theme")
        File(targetRoot, "file-uploads/temp").mkdirs()
        File(targetRoot, "file-uploads/temp/in-flight.part").writeText("in flight")
        File(targetRoot, "file-uploads/old.png").writeText("old")

        // Archive with a passphrase; the dump runs on one connection inside one snapshot.
        val bytes = ByteArrayOutputStream()
        val manifest = withConnection("ph_w6_src") { connection ->
            InstanceArchiver(source, "pano_", "1.0.0-alpha.520", ArchiveSource(instanceName = "IT"))
                .archive(bytes, PanoArcEncryption.Passphrase("correct horse".toCharArray(), t = 1, mKiB = 8192), connection)
        }

        assertEquals(mapOf("core" to 55, "pano-plugin-market" to 12), manifest.pano!!.schemeVersions)
        assertEquals(PanoNativeDumper.DUMP_TOOL, manifest.db!!.dumpTool)

        val targetConfig = JsonObject()
            .put("config-version", 30)
            .put("database", JsonObject().put("host", address).put("name", "ph_w6_dst").put("username", "root").put("password", password).put("prefix", "pano_"))
            .put("server", JsonObject().put("http-port", 18080))
            .put("file-uploads-folder", "file-uploads")

        val restorer = InstanceRestorer(target, targetConfig, mapOf("core" to 55), 30)

        // Wrong passphrase: nothing touched.
        assertThrows<PanoArcException> { restorer.stage(bytes.toByteArray().inputStream(), PanoArcKeys(passphrase = "wrong".toCharArray())) }

        var sunk: JsonObject? = null
        val result = withConnection("ph_w6_dst") { connection ->
            restorer.restore(bytes.toByteArray().inputStream(), PanoArcKeys(passphrase = "correct horse".toCharArray()), connection) { sunk = it }
        }

        assertEquals(listOf("pano_stale", "pano_user"), result.droppedTables)
        assertEquals(2500L + 3 + 2 + 4, result.dump.rows)

        val targetSnapshot = withConnection("ph_w6_dst") { snapshot(it) }

        assertEquals(sourceSnapshot.filterKeys { it.startsWith("pano_") }, targetSnapshot.filterKeys { it.startsWith("pano_") })
        assertEquals(setOf("foreign_keep", "pano_bulk", "pano_group", "pano_scheme_version", "pano_user"), targetSnapshot.keys)
        assertEquals(listOf("Integer:42"), targetSnapshot["foreign_keep"])

        withConnection("ph_w6_dst") { connection ->
            // AUTO_INCREMENT continues after the highest restored id; FKs are back on.
            connection.run("INSERT INTO pano_user (name) VALUES ('after restore')")
            val id = connection.query("SELECT MAX(id) FROM pano_user").execute().coAwait().first().getLong(0)

            assertEquals(1001L, id)
            assertTrue(runCatching { connection.run("INSERT INTO pano_user (group_id) VALUES (999)") }.isFailure)
        }

        // Files: replaced from the archive, target's in-flight uploads and license kept.
        assertFalse(File(targetRoot, "plugins/old.jar").exists())
        assertTrue(File(targetRoot, "plugins/market.jar").readBytes().contentEquals(File(sourceRoot, "plugins/market.jar").readBytes()))
        assertEquals("source theme", File(targetRoot, "themes/vanilla-theme/index.js").readText())
        assertEquals("target-license", File(targetRoot, "themes/vanilla-theme/.pano-license.jwt").readText())
        assertEquals("in flight", File(targetRoot, "file-uploads/temp/in-flight.part").readText())
        assertFalse(File(targetRoot, "file-uploads/old.png").exists())
        assertEquals(3000L, File(targetRoot, "file-uploads/post/a.png").length())
        assertTrue(File(targetRoot, "maintenance").isDirectory)
        assertFalse(File(targetRoot, ".temp").listFiles()!!.any(), "staging removed")

        val config = sunk!!

        assertEquals("ph_w6_dst", config.getJsonObject("database").getString("name"))
        assertEquals("pano_", config.getJsonObject("database").getString("prefix"))
        assertEquals("keep-this-jwt-key", config.getString("jwt-key"))
        assertEquals(18080, config.getJsonObject("server").getInteger("http-port"))
    }

    @Test
    fun `the dump is exactly what the sanitiser accepts and holds no other prefix`() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()

        withConnection("ph_w6_src") { connection ->
            if (connection.query("SHOW TABLES LIKE 'pano_group'").execute().coAwait().size() == 0) {
                seedSource(connection)
            }

            PanoNativeDumper("pano_", batchRows = 100).dumpGzip(connection, out)
        }

        val sql = GZIPInputStream(out.toByteArray().inputStream()).readBytes().toString(Charsets.UTF_8)

        assertFalse(sql.contains("other_keep"))
        assertFalse(sql.contains("AUTO_INCREMENT="))
        assertFalse(sql.contains("`doubled`) VALUES"), "generated columns are not inserted")
        assertTrue(sql.lines().count { it.startsWith("INSERT INTO `pano_bulk`") } >= 25, "bulk rows are batched")

        val summary = SanitisedSqlImporter("pano_").validate(sql.reader())

        assertEquals(setOf("pano_bulk", "pano_group", "pano_scheme_version", "pano_user"), summary.tables)
        assertEquals(ArchiveManifest.DB_DUMP_ENTRY, "db/dump.sql.gz")
        assertTrue(Files.exists(temp.toPath()))
    }
}
