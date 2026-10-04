package com.panomc.platform.backup

import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.instance.InstanceLayout
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The local backup/restore flow against a real MariaDB (the same service the panel and setup routes
 * drive): passphrase backup → changes → restore with maintenance + safety archive + config + restart
 * request; rejected archives change nothing; a failed apply rolls back to the safety archive; setup
 * mode restores into an empty database; one job at a time. Gated like the T3 IT:
 * `PANO_IT_MARIADB=host:port` + `PANO_IT_MARIADB_PASSWORD` (root), e.g. Docker `ph-w6-mariadb` on 18306.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class PanoBackupServiceMariaDbIT {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:18306"
    private val rootPassword = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "w6root"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val passphrase get() = "correct horse battery staple".toCharArray()

    private fun database(name: String, host: String = address) = JsonObject()
        .put("type", "mariadb").put("host", host).put("name", name).put("username", "root").put("password", rootPassword)
        .put("prefix", "pano_")

    inner class FakeHost(root: File, var config: JsonObject) : PanoBackupHost {
        override val layout = InstanceLayout(
            configFile = File(root, "config.conf"),
            pluginsDir = File(root, "plugins"),
            themesDir = File(root, "themes"),
            uploadsDir = File(root, "file-uploads"),
            maintenanceDir = File(root, "maintenance"),
            tempDir = File(root, ".temp")
        )
        override val panoVersion = "1.0.0-alpha.520"

        var maintenance = false
        val maintenanceCalls = mutableListOf<Boolean>()
        val applied = mutableListOf<JsonObject>()
        var failApplyConfig = 0
        var restoreApplied = 0

        override fun dbPrefix() = "pano_"
        override fun targetConfig(): JsonObject = config.copy()
        override fun knownSchemeVersions() = mapOf("core" to 56)
        override fun configVersion() = 30

        override suspend fun connect(): SqlConnection =
            MySQLConnection.connect(vertx, PanoBackupManager.connectOptions(config.getJsonObject("database"))).coAwait()

        override suspend fun setMaintenance(enabled: Boolean): Boolean {
            maintenanceCalls.add(enabled)

            return maintenance.also { maintenance = enabled }
        }

        override suspend fun applyConfig(config: JsonObject) {
            if (failApplyConfig > 0) {
                failApplyConfig--

                throw IllegalStateException("config sink failed (test)")
            }

            applied.add(config)
            this.config = config
        }

        override suspend fun restoreApplied() {
            restoreApplied++
        }
    }

    private suspend fun sql(db: String, vararg statements: String): List<List<String?>> {
        val connection = MySQLConnection.connect(vertx, PanoBackupManager.connectOptions(database(db))).coAwait()

        try {
            var last = emptyList<List<String?>>()

            statements.forEach { statement ->
                last = connection.query(statement).execute().coAwait().map { row -> (0 until row.size()).map { row.getValue(it)?.toString() } }
            }

            return last
        } finally {
            connection.close().coAwait()
        }
    }

    private suspend fun users(db: String) = sql(db, "SELECT id, name, HEX(avatar) FROM pano_user ORDER BY id")

    private lateinit var sourceRoot: File
    private lateinit var host: FakeHost
    private lateinit var service: PanoBackupService

    @BeforeEach
    fun seed() = runBlocking<Unit> {
        sql(
            "mysql",
            "DROP DATABASE IF EXISTS ph_w6_t4_a", "DROP DATABASE IF EXISTS ph_w6_t4_b",
            "CREATE DATABASE ph_w6_t4_a CHARACTER SET utf8mb4", "CREATE DATABASE ph_w6_t4_b CHARACTER SET utf8mb4"
        )
        sql(
            "ph_w6_t4_a",
            """CREATE TABLE `pano_scheme_version` (`pluginId` varchar(255), `when` timestamp not null default CURRENT_TIMESTAMP,
               `key` varchar(255) not null, `extra` varchar(255), UNIQUE INDEX `pluginId_key_idx` (`pluginId`, `key`)) ENGINE=InnoDB""",
            "INSERT INTO pano_scheme_version (pluginId, `key`, extra) VALUES (NULL, '56', 'Add the Pano Backup settings')",
            "CREATE TABLE `pano_user` (`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, `name` varchar(64) NOT NULL, `avatar` blob NULL) ENGINE=InnoDB",
            "INSERT INTO pano_user (name, avatar) VALUES ('admin', X'00FF10'), ('it''s me', NULL), ('ğüş', X'')",
            "CREATE TABLE `other_app` (`id` int) ENGINE=InnoDB",
            "INSERT INTO other_app VALUES (7)"
        )

        sourceRoot = File(temp, "source-${System.nanoTime()}")
        host = FakeHost(sourceRoot, JsonObject().put("config-version", 30).put("database", database("ph_w6_t4_a")))

        File(sourceRoot, "config.conf").apply { parentFile.mkdirs() }.writeText(
            """
            config-version = 30
            website-name = "Backed Up Site"
            jwt-key = "source-jwt"
            database {
                type = "mariadb"
                host = "$address"
                name = "ph_w6_t4_a"
                username = "root"
                password = "$rootPassword"
                prefix = "pano_"
            }
            maintenance { enabled = false }
            """.trimIndent()
        )
        File(sourceRoot, "plugins/market.jar").apply { parentFile.mkdirs() }.writeText("market v1")
        File(sourceRoot, "file-uploads/logo.png").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        File(sourceRoot, "file-uploads/temp/in-flight.bin").apply { parentFile.mkdirs() }.writeText("upload in progress")
        File(sourceRoot, "themes/vanilla/theme.json").apply { parentFile.mkdirs() }.writeText("{}")
        File(sourceRoot, "themes/vanilla/.pano-license.jwt").writeText("license token")

        service = PanoBackupService(PanoBackupStore(File(temp, "backups-${System.nanoTime()}")), host, scope)
    }

    @AfterAll
    fun cleanup() = runBlocking<Unit> {
        sql("mysql", "DROP DATABASE IF EXISTS ph_w6_t4_a", "DROP DATABASE IF EXISTS ph_w6_t4_b")
        vertx.close().coAwait()
    }

    private suspend fun mutate() {
        sql("ph_w6_t4_a", "INSERT INTO pano_user (name) VALUES ('intruder')", "DELETE FROM pano_user WHERE name = 'admin'")
        File(sourceRoot, "plugins/evil.jar").writeText("evil")
        File(sourceRoot, "file-uploads/logo.png").writeBytes(byteArrayOf(9))
    }

    private fun restoreDirs() = File(sourceRoot, ".temp").listFiles()?.filter { it.name.startsWith("restore-") } ?: emptyList()

    @Test
    fun `an encrypted backup restores the database and files with maintenance, a safety archive and a restart`() = runBlocking {
        val original = users("ph_w6_t4_a")
        val backup = service.tryCreate(passphrase, PanoBackupTag.MANUAL, "admin")!!

        assertTrue(backup.encrypted)
        assertTrue(backup.sizeBytes > 0)
        assertEquals(listOf(backup.id), service.store.list().map { it.id })

        mutate()
        val mutated = users("ph_w6_t4_a")

        val safety = service.restore(service.store.archiveFile(backup.id), passphrase)!!

        assertEquals(original, users("ph_w6_t4_a"))
        assertEquals(listOf(listOf("7")), sql("ph_w6_t4_a", "SELECT id FROM other_app"))
        assertFalse(File(sourceRoot, "plugins/evil.jar").exists())
        assertEquals("market v1", File(sourceRoot, "plugins/market.jar").readText())
        assertEquals(listOf<Byte>(1, 2, 3), File(sourceRoot, "file-uploads/logo.png").readBytes().toList())
        assertEquals("upload in progress", File(sourceRoot, "file-uploads/temp/in-flight.bin").readText())
        assertEquals("license token", File(sourceRoot, "themes/vanilla/.pano-license.jwt").readText())

        assertEquals(listOf(true), host.maintenanceCalls)
        assertEquals(1, host.restoreApplied)
        assertEquals(1, host.applied.size)
        assertEquals("Backed Up Site", host.applied[0].getString("website-name"))
        assertEquals("ph_w6_t4_a", host.applied[0].getJsonObject("database").getString("name"))
        assertTrue(restoreDirs().isEmpty())

        // The safety archive holds the state right before the restore.
        assertEquals(PanoBackupTag.PRE_RESTORE, safety.tag)
        assertFalse(safety.encrypted)
        service.restore(service.store.archiveFile(safety.id), null, safetyArchive = false, maintenance = false)
        assertEquals(mutated, users("ph_w6_t4_a"))
        assertEquals("evil", File(sourceRoot, "plugins/evil.jar").readText())
    }

    @Test
    fun `a wrong or missing passphrase is refused before anything changes`() = runBlocking {
        val backup = service.tryCreate(passphrase, PanoBackupTag.MANUAL)!!

        mutate()
        val mutated = users("ph_w6_t4_a")

        val wrong = assertThrows<PanoArcException> {
            runBlocking { service.restore(service.store.archiveFile(backup.id), "wrong".toCharArray()) }
        }
        val missing = assertThrows<PanoArcException> {
            runBlocking { service.restore(service.store.archiveFile(backup.id), null) }
        }

        assertEquals(PanoArcException.Code.WRONG_PASSPHRASE, wrong.code)
        assertEquals(PanoArcException.Code.PASSPHRASE_REQUIRED, missing.code)
        assertEquals("WRONG_PASSPHRASE" to false, PanoBackupService.describe(wrong))
        assertEquals(mutated, users("ph_w6_t4_a"))
        assertTrue(File(sourceRoot, "plugins/evil.jar").exists())
        assertTrue(host.maintenanceCalls.isEmpty())
        assertEquals(0, host.restoreApplied)
        assertEquals(listOf(backup.id), service.store.list().map { it.id })
        assertTrue(restoreDirs().isEmpty())
    }

    @Test
    fun `a restore that fails half way is rolled back to the safety archive`() = runBlocking {
        val backup = service.tryCreate(null, PanoBackupTag.MANUAL)!!

        assertFalse(backup.encrypted)

        mutate()
        val mutated = users("ph_w6_t4_a")

        // The config is the last step: the database and files are already replaced when it fails.
        host.failApplyConfig = 1

        val error = assertThrows<PanoBackupException> {
            runBlocking { service.restore(service.store.archiveFile(backup.id), null) }
        }

        assertEquals(PanoBackupService.RESTORE_FAILED, error.code)
        assertTrue(error.rolledBack)
        assertEquals(PanoBackupService.RESTORE_FAILED to true, PanoBackupService.describe(error))
        assertEquals(mutated, users("ph_w6_t4_a"))
        assertEquals("evil", File(sourceRoot, "plugins/evil.jar").readText())
        assertEquals(listOf<Byte>(9), File(sourceRoot, "file-uploads/logo.png").readBytes().toList())
        assertEquals(listOf(true, false), host.maintenanceCalls)
        assertEquals(0, host.restoreApplied)
        assertEquals(1, host.applied.size)
        assertEquals(
            setOf(PanoBackupTag.MANUAL, PanoBackupTag.PRE_RESTORE),
            service.store.list().map { it.tag }.toSet()
        )
    }

    @Test
    fun `setup mode restores into an empty database without maintenance or a safety archive`() = runBlocking {
        val backup = service.tryCreate(passphrase, PanoBackupTag.MANUAL)!!
        val original = users("ph_w6_t4_a")

        val targetRoot = File(temp, "fresh-${System.nanoTime()}")
        val target = FakeHost(targetRoot, JsonObject().put("config-version", 30).put("database", database("ph_w6_t4_b")))
        val setup = PanoBackupService(PanoBackupStore(File(temp, "setup-backups-${System.nanoTime()}")), target, scope)

        assertNull(setup.restore(service.store.archiveFile(backup.id), passphrase, safetyArchive = false, maintenance = false))

        assertEquals(original, users("ph_w6_t4_b"))
        assertEquals("market v1", File(targetRoot, "plugins/market.jar").readText())
        assertTrue(target.maintenanceCalls.isEmpty())
        assertTrue(setup.store.list().isEmpty())
        assertEquals(1, target.restoreApplied)
        assertEquals("ph_w6_t4_b", target.applied.single().getJsonObject("database").getString("name"))
        assertEquals("source-jwt", target.applied.single().getString("jwt-key"))
    }

    @Test
    fun `an unreachable database fails before maintenance mode or a safety archive`() = runBlocking {
        val backup = service.tryCreate(null, PanoBackupTag.MANUAL)!!

        host.config = host.config.copy().put("database", database("ph_w6_t4_a", host = "127.0.0.1:1"))

        val error = assertThrows<PanoBackupException> {
            runBlocking { service.restore(service.store.archiveFile(backup.id), null) }
        }

        assertEquals(PanoBackupService.DATABASE_CONNECTION_FAILED, error.code)
        assertTrue(host.maintenanceCalls.isEmpty())
        assertEquals(1, service.store.list().size)
    }

    private suspend fun awaitJob(): PanoBackupJob = withTimeout(120_000) {
        while (service.job?.status == PanoBackupJob.Status.RUNNING || service.isBusy()) {
            delay(50)
        }

        service.job!!
    }

    @Test
    fun `background jobs run one at a time and report their outcome`() = runBlocking {
        val first = service.startCreate(passphrase, PanoBackupTag.MANUAL, "admin")

        val busy = assertThrows<PanoBackupException> { service.startCreate(null) }
        assertEquals(PanoBackupService.BUSY, busy.code)

        val created = awaitJob()

        assertEquals(first.id, created.id)
        assertEquals(PanoBackupJob.Status.DONE, created.status)
        assertNotNull(created.backupId)
        assertEquals("admin", service.store.get(created.backupId!!)!!.createdBy)

        val upload = File(temp, "upload-${System.nanoTime()}.panoarc")
        service.store.archiveFile(created.backupId!!).copyTo(upload)

        service.startRestore(upload, "nope".toCharArray(), deleteSource = true)
        val failed = awaitJob()

        assertEquals(PanoBackupJob.Status.FAILED, failed.status)
        assertEquals("WRONG_PASSPHRASE", failed.error)
        assertFalse(failed.rolledBack)
        assertFalse(upload.exists())
        assertEquals("FAILED", failed.toJson().getString("status"))
    }
}
