package com.panomc.platform.backup

import com.panomc.platform.archive.instance.InstanceLayout
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Deleting the pre-restore safety backup while a restore is applying (from the panel, say) no longer leaves the
 * rollback without it: the restore holds the file open from the moment it exists. Gated: `PANO_IT_MARIADB`,
 * `PANO_IT_MARIADB_PASSWORD`, `PANO_IT_MARIADB_DATABASE` (a scratch database whose `pano_` tables this test owns).
 */
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class PanoBackupRollbackDeleteMariaDbIT {
    @TempDir
    lateinit var temp: File

    private val vertx = Vertx.vertx()
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:3306"
    private val password = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "pano"
    private val name = System.getenv("PANO_IT_MARIADB_DATABASE") ?: "pano_it_dao"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = JsonObject()
        .put("type", "mariadb").put("host", address).put("name", name).put("username", "root").put("password", password)
        .put("prefix", "pano_")

    inner class FakeHost(root: File) : PanoBackupHost {
        override val layout = InstanceLayout(
            configFile = File(root, "config.conf"), pluginsDir = File(root, "plugins"), themesDir = File(root, "themes"),
            uploadsDir = File(root, "file-uploads"), maintenanceDir = File(root, "maintenance"), tempDir = File(root, ".temp")
        )
        override val panoVersion = "1.0.0-alpha.520"

        var config: JsonObject = JsonObject().put("config-version", 30).put("database", database)
        var duringFailingApply: () -> Unit = {}
        var failApplyConfig = 0

        override fun dbPrefix() = "pano_"
        override fun targetConfig(): JsonObject = config.copy()
        override fun knownSchemeVersions() = mapOf("core" to 56)
        override fun configVersion() = 30

        override suspend fun connect(): SqlConnection =
            MySQLConnection.connect(vertx, PanoBackupManager.connectOptions(config.getJsonObject("database"))).coAwait()

        override suspend fun setMaintenance(enabled: Boolean): Boolean = false

        override suspend fun applyConfig(config: JsonObject) {
            if (failApplyConfig > 0) {
                failApplyConfig--
                duringFailingApply()

                throw IllegalStateException("config sink failed (test)")
            }

            this.config = config
        }

        override suspend fun restoreApplied() {}
    }

    private suspend fun sql(vararg statements: String): List<String> {
        val connection = MySQLConnection.connect(vertx, PanoBackupManager.connectOptions(database)).coAwait()

        try {
            var last = emptyList<String>()

            statements.forEach { statement ->
                last = connection.query(statement).execute().coAwait().map { it.getValue(0).toString() }
            }

            return last
        } finally {
            connection.close().coAwait()
        }
    }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> {
        sql("DROP TABLE IF EXISTS pano_scheme_version, pano_user")
        vertx.close().coAwait()
    }

    @Test
    fun `the rollback works although the safety backup was deleted during the apply`() = runBlocking {
        sql(
            "DROP TABLE IF EXISTS pano_scheme_version, pano_user",
            """CREATE TABLE `pano_scheme_version` (`pluginId` varchar(255), `when` timestamp not null default CURRENT_TIMESTAMP,
               `key` varchar(255) not null, `extra` varchar(255)) ENGINE=InnoDB""",
            "INSERT INTO pano_scheme_version (`key`) VALUES ('56')",
            "CREATE TABLE `pano_user` (`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, `name` varchar(64) NOT NULL) ENGINE=InnoDB",
            "INSERT INTO pano_user (name) VALUES ('admin')"
        )

        val root = File(temp, "instance")
        val host = FakeHost(root)

        File(root, "config.conf").apply { parentFile.mkdirs() }.writeText(
            "config-version = 30\ndatabase {\n  type = \"mariadb\"\n  host = \"$address\"\n  name = \"$name\"\n  prefix = \"pano_\"\n}\n"
        )

        val service = PanoBackupService(PanoBackupStore(File(temp, "backups")), host, scope)
        val backup = service.tryCreate(null, PanoBackupTag.MANUAL)!!

        sql("INSERT INTO pano_user (name) VALUES ('after the backup')")

        host.failApplyConfig = 1
        host.duringFailingApply = {
            service.store.list().filter { it.tag == PanoBackupTag.PRE_RESTORE }.forEach { runCatching { service.store.delete(it.id) } }
        }

        val error = assertThrows<PanoBackupException> {
            runBlocking { service.restore(service.store.archiveFile(backup.id), null) }
        }

        assertEquals(PanoBackupService.RESTORE_FAILED, error.code)
        assertTrue(error.rolledBack)
        assertEquals(listOf("admin", "after the backup"), sql("SELECT name FROM pano_user ORDER BY id"))
    }
}
