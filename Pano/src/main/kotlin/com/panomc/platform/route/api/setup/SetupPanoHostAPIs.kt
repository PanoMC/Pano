package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.LinkPurpose
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.body
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.host
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/*
 * setup-ui "import from Pano Backup / Pano Host": a BACKUP link made during setup (kept in memory
 * only), the account's Pano Backups, and a restore of one of them into this new install. The restore
 * job is polled with `GET /api/setup/restore`, like a restore from a file.
 */

/**
 * `POST /api/setup/pano-host/link` → `{code, verifyUrl, expiresAt, interval}`;
 * `POST /api/setup/pano-host/link/poll` → `{status NONE|PENDING|LINKED|EXPIRED, link?}`.
 */
@Endpoint
class SetupPanoHostLinkAPI(private val panoBackupManager: PanoBackupManager) : SetupApi() {
    override val paths = listOf(
        Path("/api/setup/pano-host/link", RouteType.POST),
        Path("/api/setup/pano-host/link/poll", RouteType.POST)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val remote = panoBackupManager.setupRemote

        if (context.normalizedPath().endsWith("/poll")) {
            return Successful(host { remote.pollLink(LinkPurpose.BACKUP) }.map)
        }

        return Successful(host { remote.startLink(LinkPurpose.BACKUP) }.toPublicJson().map)
    }
}

/** `GET /api/setup/pano-host/backups` → `{backups (pano-instance, DONE), tier, usage}`. */
@Endpoint
class SetupGetPanoHostBackupsAPI(private val panoBackupManager: PanoBackupManager) : SetupApi() {
    override val paths = listOf(Path("/api/setup/pano-host/backups", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val list = host { panoBackupManager.setupRemote.listBackups() }
        val backups = (list.getJsonArray("backups") ?: JsonArray())
            .filterIsInstance<JsonObject>()
            .filter { it.getString("kind") == "pano-instance" && it.getString("status") == "DONE" }

        list.put("backups", JsonArray(backups))

        return Successful(list.map)
    }
}

/**
 * `POST /api/setup/pano-host/restore {backupId, passphrase, host?, dbName?, username?, password?}` →
 * `{job}`: downloads that Pano Backup, verifies it with the passphrase and restores it into the
 * target database (omitted = the one from setup step 2), then Pano restarts as the restored site.
 */
@Endpoint
class SetupRestorePanoHostBackupAPI(private val panoBackupManager: PanoBackupManager) : SetupApi() {
    override val paths = listOf(Path("/api/setup/pano-host/restore", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val body = body(context)
        val backupId = body.getString("backupId")?.takeIf { PanoRemoteBackupService.isValidRemoteId(it) } ?: throw NotExists()
        val remote = panoBackupManager.setupRemote

        PanoBackupRemoteRoutes.requireReady(remote, LinkPurpose.BACKUP, passphrase = false)

        val hostAddress = body.getString("host")?.trim()
        val database = if (hostAddress.isNullOrEmpty()) null else JsonObject()
            .put("host", hostAddress)
            .put("name", body.getString("dbName") ?: "")
            .put("username", body.getString("username") ?: "")
            .put("password", body.getString("password") ?: "")

        val passphrase = PanoBackupRoutes.passphrase(body.getString("passphrase") ?: throw BadRequest())
        val service = panoBackupManager.prepareSetupService(database)

        val job = PanoBackupRemoteRoutes.job {
            remote.startRestore(backupId, passphrase, service = service, safetyArchive = false, maintenance = false)
        }

        return Successful(mapOf("job" to job.toJson()))
    }
}
