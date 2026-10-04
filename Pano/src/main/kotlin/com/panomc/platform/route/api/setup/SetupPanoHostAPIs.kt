package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.config.ConfigManager
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
 * setup-ui "import from Pano Backup / Pano Host": over the panomc.com account connected with the setup
 * step-4 connect endpoints (`/api/setup/steps/4/platform/code` + `/connect`; they only touch the config,
 * so setup-ui's transfer dialog uses them from step 0 too, and the account stays connected for step 4;
 * not connected → `hostError CONNECT_REQUIRED`, revoked → `INVALID_TOKEN`), the account's
 * Pano Backups and a restore of one of them into this new install. The restore job is polled with
 * `GET /api/setup/restore`, like a restore from a file.
 */

/**
 * `GET /api/setup/pano-host/backups` → `{backups (pano-instance, DONE, every Pano of the account, newest
 * first, each with `instanceName`), tier, usage, account {username}}`.
 */
@Endpoint
class SetupGetPanoHostBackupsAPI(
    private val panoBackupManager: PanoBackupManager,
    private val platformConfig: ConfigManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/pano-host/backups", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val remote = panoBackupManager.setupRemote

        PanoBackupRemoteRoutes.requireReady(remote, passphrase = false)

        val list = host { remote.listBackups() }
        val backups = (list.getJsonArray("panos") ?: JsonArray())
            .filterIsInstance<JsonObject>()
            .flatMap { pano -> (pano.getJsonArray("backups") ?: JsonArray()).filterIsInstance<JsonObject>() }
            .filter { it.getString("kind") == "pano-instance" && it.getString("status") == "DONE" }
            .sortedByDescending { it.getLong("createdAt", 0L) }

        return Successful(
            mapOf(
                "backups" to JsonArray(backups),
                "tier" to list.getJsonObject("tier"),
                "usage" to list.getJsonObject("usage"),
                "account" to mapOf("username" to platformConfig.config.panoAccount.username)
            )
        )
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

        PanoBackupRemoteRoutes.requireReady(remote, passphrase = false)

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

/**
 * `GET /api/setup/pano-host/instances` → `{workloads[{id, name, label, state, exportable, reason?, export}],
 * account {username}}`: the connected account's Pano Host instances setup-ui can move here.
 */
@Endpoint
class SetupGetPanoHostInstancesAPI(
    private val panoBackupManager: PanoBackupManager,
    private val platformConfig: ConfigManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/pano-host/instances", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val remote = panoBackupManager.setupRemote

        PanoBackupRemoteRoutes.requireReady(remote, passphrase = false)

        val list = host { remote.listHostInstances() }

        return Successful(
            mapOf(
                "workloads" to (list.getJsonArray("workloads") ?: JsonArray()),
                "account" to mapOf("username" to platformConfig.config.panoAccount.username)
            )
        )
    }
}

/**
 * `POST /api/setup/pano-host/instances/:id/move {host?, dbName?, username?, password?}` → `{job}`: exports that
 * Pano Host instance (or reuses its valid export), downloads it and restores it into the target database
 * (omitted = the one from setup step 2); Pano restarts as the moved site. Polled with `GET /api/setup/restore`.
 */
@Endpoint
class SetupMovePanoHostInstanceAPI(private val panoBackupManager: PanoBackupManager) : SetupApi() {
    override val paths = listOf(Path("/api/setup/pano-host/instances/:id/move", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val workloadId = context.pathParam("id")
            ?.takeIf { it.length in 3..64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
            ?: throw NotExists()
        val body = body(context)
        val remote = panoBackupManager.setupRemote

        PanoBackupRemoteRoutes.requireReady(remote, passphrase = false)

        val hostAddress = body.getString("host")?.trim()
        val database = if (hostAddress.isNullOrEmpty()) null else JsonObject()
            .put("host", hostAddress)
            .put("name", body.getString("dbName") ?: "")
            .put("username", body.getString("username") ?: "")
            .put("password", body.getString("password") ?: "")

        val service = panoBackupManager.prepareSetupService(database)

        val job = PanoBackupRemoteRoutes.job { remote.startHostMove(workloadId, service = service) }

        return Successful(mapOf("job" to job.toJson()))
    }
}
