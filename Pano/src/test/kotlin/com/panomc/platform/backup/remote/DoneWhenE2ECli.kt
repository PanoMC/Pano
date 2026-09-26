package com.panomc.platform.backup.remote

import com.panomc.platform.archive.instance.InstanceLayout
import com.panomc.platform.backup.PanoBackupHost
import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.PanoBackupService
import com.panomc.platform.backup.PanoBackupStore
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
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * The platform half of the W6 done-when e2e (`.worktrees/w6/run/e2e-w6.sh`), run as a plain JVM on
 * the Pano test classpath against the local W6 control plane, the real bucket and `ph-w6-mariadb`:
 *
 * - `create <dir>`: a self-hosted Pano (MariaDB db `ph_w6_e2e_src` + files under `<dir>/src`) links
 *   to Pano Host with the device-code flow (approved with the owner's bearer, as the browser would),
 *   sets a random E2E passphrase (`<dir>/passphrase`, 0600) and uploads a Pano Backup with the real
 *   [PanoRemoteBackupService] (archive → envelope → presigned multipart parts → complete). Writes
 *   `<dir>/pano-backup.json {backupId, sizeBytes, expect}`.
 * - `restore <archive> <passphraseFile> <result.json>`: what the Portal agent runs for BACKUP_RESTORE —
 *   the platform restorer ([PanoBackupService.startRestore], no maintenance/safety: the agent took
 *   the safety archive) into db `ph_w6_e2e_dst` + `<dir>/dst`, then `{rows}` in the same shape as
 *   `expect`. Exit 0 = restored, 3 = WRONG_PASSPHRASE, 1 = anything else.
 *
 * Env: `PANO_IT_MARIADB` (host:port), `PANO_IT_MARIADB_PASSWORD` (root), for create also
 * `PANO_E2E_API` (`http://127.0.0.1:18097/api`) and `PANO_E2E_OWNER_TOKEN_FILE`.
 */
object DoneWhenE2ECli {
    private const val SRC_DB = "ph_w6_e2e_src"
    private const val DST_DB = "ph_w6_e2e_dst"

    private val vertx = Vertx.vertx()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:18306"
    private val rootPassword = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "w6root"

    private fun database(name: String) = JsonObject()
        .put("type", "mariadb").put("host", address).put("name", name).put("username", "root").put("password", rootPassword)
        .put("prefix", "pano_")

    private class Host(root: File, private val config: JsonObject) : PanoBackupHost {
        override val layout = InstanceLayout(
            configFile = File(root, "config.conf"),
            pluginsDir = File(root, "plugins"),
            themesDir = File(root, "themes"),
            uploadsDir = File(root, "file-uploads"),
            maintenanceDir = File(root, "maintenance"),
            tempDir = File(root, ".temp")
        )
        override val panoVersion = "1.0.0-alpha.520"

        override fun dbPrefix() = "pano_"
        override fun targetConfig(): JsonObject = config.copy()
        override fun knownSchemeVersions() = mapOf("core" to 56)
        override fun configVersion() = 30

        override suspend fun connect(): SqlConnection =
            MySQLConnection.connect(vertx, PanoBackupManager.connectOptions(config.getJsonObject("database"))).coAwait()

        override suspend fun setMaintenance(enabled: Boolean) = false
        override suspend fun applyConfig(config: JsonObject) = layout.configFile.writeText(config.encodePrettily())
        override suspend fun restoreApplied() = Unit
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

    /** What must survive the trip: users (incl. binary + unicode), scheme rows, one upload, one plugin. */
    private suspend fun snapshot(db: String, root: File) = JsonObject()
        .put("users", sql(db, "SELECT id, name, HEX(avatar) FROM pano_user ORDER BY id").joinToString(";") { it.joinToString("|") })
        .put("schemeRows", sql(db, "SELECT COUNT(*) FROM pano_scheme_version").single().single())
        .put("logo", File(root, "file-uploads/logo.png").takeIf { it.exists() }?.readBytes()?.joinToString(",") ?: "")
        .put("plugin", File(root, "plugins/market.jar").takeIf { it.exists() }?.readText() ?: "")

    private suspend fun awaitJob(job: PanoBackupJob, what: String): PanoBackupJob {
        while (job.status == PanoBackupJob.Status.RUNNING) delay(200)

        println("$what: ${job.status} ${job.error ?: ""} ${job.message ?: ""}".trim())

        return job
    }

    private fun secretFile(file: File, value: String) {
        file.delete()
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        file.writeText(value)
    }

    private suspend fun create(dir: File): Int {
        val api = System.getenv("PANO_E2E_API") ?: error("PANO_E2E_API")
        val ownerToken = File(System.getenv("PANO_E2E_OWNER_TOKEN_FILE") ?: error("PANO_E2E_OWNER_TOKEN_FILE")).readText().trim()
        val root = File(dir, "src").apply { deleteRecursively(); mkdirs() }

        sql("mysql", "DROP DATABASE IF EXISTS $SRC_DB", "CREATE DATABASE $SRC_DB CHARACTER SET utf8mb4")
        sql(
            SRC_DB,
            """CREATE TABLE `pano_scheme_version` (`pluginId` varchar(255), `when` timestamp not null default CURRENT_TIMESTAMP,
               `key` varchar(255) not null, `extra` varchar(255), UNIQUE INDEX `pluginId_key_idx` (`pluginId`, `key`)) ENGINE=InnoDB""",
            "INSERT INTO pano_scheme_version (pluginId, `key`, extra) VALUES (NULL, '56', 'Add the Pano Backup settings')",
            "CREATE TABLE `pano_user` (`id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, `name` varchar(64) NOT NULL, `avatar` blob NULL) ENGINE=InnoDB",
            "INSERT INTO pano_user (name, avatar) VALUES ('admin', X'00FF10'), ('it''s me', NULL), ('ğüş self-host', X'')"
        )

        val config = JsonObject().put("config-version", 30).put("website-name", "W6 self-hosted").put("database", database(SRC_DB))

        File(root, "config.conf").writeText(config.encodePrettily())
        File(root, "plugins/market.jar").apply { parentFile.mkdirs() }.writeText("market v1")
        File(root, "file-uploads/logo.png").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3, 0x7f))

        val runner = PanoBackupService(PanoBackupStore(File(root, "pano-backups")), Host(root, config), scope)
        val remote = PanoRemoteBackupService(
            client = PanoHostClient({ api }),
            stateStore = MemoryRemoteStateStore(),
            passphraseFile = PassphraseFile(File(root, "pano-backups/${PassphraseFile.FILE_NAME}")),
            backups = runner,
            tempDir = { File(root, ".temp") },
            instanceName = { "W6 e2e self-host" },
            panoVersion = "1.0.0-alpha.520"
        )

        // Device-code link, approved by the owner exactly like the panomc.com approval page does.
        val pending = remote.startLink(LinkPurpose.BACKUP)
        val approve = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$api/host/link/approve"))
                .header("Authorization", "Bearer $ownerToken").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonObject().put("code", pending.code).encode())).build(),
            HttpResponse.BodyHandlers.ofString()
        )

        check(approve.statusCode() == 200) { "approve: ${approve.statusCode()} ${approve.body().take(300)}" }

        var status = ""

        repeat(30) {
            if (status != PanoRemoteBackupService.LINKED) {
                status = remote.pollLink(LinkPurpose.BACKUP).getString("status")
                if (status != PanoRemoteBackupService.LINKED) delay(1000)
            }
        }

        check(status == PanoRemoteBackupService.LINKED) { "link status $status" }
        println("linked (code ${pending.code})")

        val passphrase = "w6-e2e-" + ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        secretFile(File(dir, "passphrase"), passphrase)
        remote.setPassphrase(passphrase.toCharArray())

        val job = awaitJob(remote.startUpload(), "upload")

        if (job.status != PanoBackupJob.Status.DONE) return 1

        val backupId = job.remoteId!!
        val listed = remote.listBackups().getJsonArray("backups").map { it as JsonObject }.single { it.getString("id") == backupId }

        check(listed.getString("status") == "DONE" && listed.getString("kind") == "pano-instance") { "listed: $listed" }

        File(dir, "pano-backup.json").writeText(
            JsonObject().put("backupId", backupId).put("sizeBytes", listed.getLong("sizeBytes")).put("expect", snapshot(SRC_DB, root)).encodePrettily()
        )
        println("Pano Backup $backupId uploaded (${listed.getLong("sizeBytes")} B)")

        return 0
    }

    private suspend fun restore(archive: File, passphraseFile: File, result: File): Int {
        val dir = result.parentFile
        val root = File(dir, "dst").apply { deleteRecursively(); mkdirs() }

        sql("mysql", "DROP DATABASE IF EXISTS $DST_DB", "CREATE DATABASE $DST_DB CHARACTER SET utf8mb4")

        val config = JsonObject().put("config-version", 30).put("website-name", "Pano Host target").put("database", database(DST_DB))
        val runner = PanoBackupService(PanoBackupStore(File(root, "pano-backups")), Host(root, config), scope)
        val passphrase = passphraseFile.readText().takeIf { it.isNotEmpty() }?.toCharArray()
        val job = awaitJob(runner.startRestore(archive, passphrase, deleteSource = false, safetyArchive = false, maintenance = false), "restore")

        if (job.status != PanoBackupJob.Status.DONE) {
            val empty = sql("mysql", "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$DST_DB'").single().single()

            // A refused archive must not have touched the target.
            check(empty == "0") { "target changed after a refused restore ($empty tables)" }

            return if (job.error == "WRONG_PASSPHRASE") 3 else 1
        }

        result.writeText(JsonObject().put("rows", snapshot(DST_DB, root)).encodePrettily())
        println("restored into $DST_DB")

        return 0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            runBlocking {
                when (args.firstOrNull()) {
                    "create" -> create(File(args[1]))
                    "restore" -> restore(File(args[1]), File(args[2]), File(args[3]))
                    else -> { System.err.println("usage: create <dir> | restore <archive> <passphraseFile> <result.json>"); 2 }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }

        vertx.close()
        exitProcess(code)
    }
}
