package com.panomc.platform.route.api.panel.panoBackup.remote

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.PanoBackupActionLog
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.backup.McServerBackupSources
import com.panomc.platform.backup.PanoBackupAudit
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.backup.remote.PassphraseFile
import com.panomc.platform.backup.remote.RemoteBackupSettings
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.PanoHostError
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.body
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.host
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.job
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.remoteId
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.requireReady
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.workloadId
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/*
 * Panel routes of Pano Backup (the E2E-encrypted remote target on panomc.com) and of the transfer to
 * Pano Host, both over this Pano's panomc.com platform connection (not connected → `hostError
 * CONNECT_REQUIRED`). All need MANAGE_PANO_BACKUPS; the background jobs are polled with
 * `GET /api/v1/panel/pano-backups/job`. Pano Host errors come back as `PANO_HOST_ERROR {hostError, …}`.
 */

/** Base: JSON bodies are read by hand (several paths/methods per class), permission checked first. */
abstract class PanoBackupRemoteApi(protected val authProvider: AuthProvider) : PanelApi() {
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    protected fun method(context: RoutingContext): HttpMethod = context.request().method()

    protected suspend fun requireManage(context: RoutingContext) = authProvider.requirePermission(ManagePanoBackupsPermission(), context)
}

/**
 * `GET /api/v1/panel/pano-backups/remote[?fresh=true]` → `{connected, account {username, platformId}, plan
 * {tier, subscription} | null, usage {used, reserved, quota, free} | null, settings, passphraseSet,
 * lastUploadAt, hostError?, apiUrl, job, busy}` (plan + usage cached for a minute unless `fresh`).
 */
@Endpoint
class PanelGetPanoBackupRemoteAPI(authProvider: AuthProvider, private val panoBackupManager: PanoBackupManager) :
    PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/pano-backups/remote", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val status = panoBackupManager.remote.status(fresh = context.queryParam("fresh").firstOrNull() == "true")
        val service = panoBackupManager.service

        status.put("apiUrl", panoBackupManager.hostApiUrl())
        status.put("job", service.job?.toJson())
        status.put("busy", service.isBusy())

        return Successful(status.map)
    }
}

/**
 * `PUT …/remote/settings {schedule OFF|DAILY|WEEKLY, hour, mcServerIds}`;
 * `PUT …/remote/passphrase {currentPassword, passphrase}` (≥ 8 characters; kept on this server only,
 * lost = the remote backups are unrecoverable; changing it does not re-encrypt older backups).
 */
@Endpoint
class PanelUpdatePanoBackupRemoteAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/pano-backups/remote/settings", RouteType.PUT),
        Path("/pano-backups/remote/passphrase", RouteType.PUT)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val body = body(context)
        val remote = panoBackupManager.remote

        if (context.normalizedPath().endsWith("/settings")) {
            val settings = RemoteBackupSettings.fromJson(body) ?: throw BadRequest()

            remote.saveSettings(settings)

            panoBackupAudit.log(context, PanoBackupActionLog.ACTION_REMOTE_SETTINGS)

            return Successful(mapOf("settings" to settings.toJson()))
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, body.getString("currentPassword") ?: "", getSqlClient())) {
            throw CurrentPasswordNotCorrect()
        }

        val passphrase = body.getString("passphrase") ?: throw BadRequest()

        if (passphrase.length < PassphraseFile.MIN_LENGTH || passphrase.length > PanoBackupRoutes.MAX_PASSPHRASE_LENGTH) {
            throw BadRequest(extras = mapOf("minLength" to PassphraseFile.MIN_LENGTH))
        }

        context.vertx().executeBlocking { remote.setPassphrase(passphrase.toCharArray()) }.coAwait()

        // Only that it was set: the passphrase itself never reaches a log.
        panoBackupAudit.log(context, PanoBackupActionLog.ACTION_PASSPHRASE)

        return Successful(mapOf("passphraseSet" to true))
    }
}

/**
 * `GET …/remote/backups` → `{instanceId, tier, subscription, usage, panos[]}` (every Pano of the account
 * with its backups, this one first with `current: true`; `own` = this Pano's);
 * `POST …/remote/backups` → `{job}` (archive with the saved passphrase + upload).
 */
@Endpoint
class PanelPanoBackupRemoteBackupsAPI(
    authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/pano-backups/remote/backups", RouteType.ROUTE))

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val remote = panoBackupManager.remote

        return when (method(context)) {
            HttpMethod.GET -> Successful(host { remote.listBackups() }.map)
            HttpMethod.POST -> {
                // A passphrase is recommended, not required: without one the archive is uploaded plain.
                requireReady(remote, passphrase = false)

                val job = job { remote.startUpload() }

                panoBackupAudit.log(context, PanoBackupActionLog.ACTION_UPLOAD)

                Successful(mapOf("job" to job.toJson()))
            }

            else -> throw BadRequest()
        }
    }
}

/**
 * `POST …/remote/backups/:id/restore {currentPassword, passphrase?}` → `{job}` (download, verify,
 * then the local restore flow: maintenance, safety archive, restart; passphrase omitted = the saved
 * one; any backup of the account); `DELETE …/remote/backups/:id` (this Pano's own backups only).
 */
@Endpoint
class PanelPanoBackupRemoteBackupAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/pano-backups/remote/backups/:id/restore", RouteType.POST),
        Path("/pano-backups/remote/backups/:id", RouteType.DELETE)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val id = remoteId(context)
        val remote = panoBackupManager.remote

        if (method(context) == HttpMethod.DELETE) {
            host { remote.deleteBackup(id) }

            panoBackupAudit.log(context, PanoBackupActionLog.ACTION_DELETE_REMOTE, id)

            return Successful()
        }

        val body = body(context)
        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, body.getString("currentPassword") ?: "", getSqlClient())) {
            throw CurrentPasswordNotCorrect()
        }

        requireReady(remote, passphrase = false)

        val passphrase = PanoBackupRoutes.passphrase(body.getString("passphrase"))

        val job = job { remote.startRestore(id, passphrase) }

        // Written into the restored database once the restore is applied (it replaces the log).
        panoBackupAudit.deferRestore(context, PanoBackupActionLog.ACTION_RESTORE_REMOTE, id)

        return Successful(mapOf("job" to job.toJson()))
    }
}

/**
 * Transfer to Pano Host: `GET …/remote/workloads` → `{workloads[{id, name, label, state, maxBytes}]}`
 * (the account's Pano workloads); `GET …/remote/transfers` → `{transfers}` (pushed by this Pano);
 * `POST …/remote/transfers {workloadId}` → `{job}` (plain archive pushed; then the owner confirms on
 * panomc.com); `GET …/remote/transfers/:id` → `{transfer}`; `DELETE …/remote/transfers/:id` cancels a
 * pending one.
 */
@Endpoint
class PanelPanoBackupRemoteTransfersAPI(
    authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/pano-backups/remote/workloads", RouteType.GET),
        Path("/pano-backups/remote/transfers", RouteType.ROUTE),
        Path("/pano-backups/remote/transfers/:id", RouteType.ROUTE)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val remote = panoBackupManager.remote

        if (context.normalizedPath().endsWith("/workloads")) {
            return Successful(host { remote.listWorkloads() }.map)
        }

        val single = context.pathParam("id") != null

        return when (method(context)) {
            HttpMethod.GET -> if (single) {
                Successful(mapOf("transfer" to host { remote.getTransfer(remoteId(context)) }))
            } else {
                Successful(host { remote.listTransfers() }.map)
            }

            HttpMethod.POST -> {
                if (single) throw BadRequest()

                val workloadId = workloadId(body(context).getValue("workloadId") as? String)

                requireReady(remote, passphrase = false)

                val job = job { remote.startTransfer(workloadId) }

                panoBackupAudit.log(context, PanoBackupActionLog.ACTION_TRANSFER)

                Successful(mapOf("job" to job.toJson()))
            }

            HttpMethod.DELETE -> {
                if (!single) throw BadRequest()

                val transferId = remoteId(context)

                host { remote.cancelTransfer(transferId) }

                panoBackupAudit.log(context, PanoBackupActionLog.ACTION_TRANSFER_CANCEL, transferId)

                Successful()
            }

            else -> throw BadRequest()
        }
    }
}

/** `POST /api/v1/panel/servers/:id/backups/:backupId/pano-backup` → `{job}`: one MC server backup to Pano Backup. */
@Endpoint
class PanelUploadServerBackupToPanoBackupAPI(
    authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager,
    private val mcServerBackupSources: McServerBackupSources
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/servers/:id/backups/:backupId/pano-backup", RouteType.POST))

    override suspend fun handle(context: RoutingContext): Result {
        val serverId = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()
        val backupId = context.pathParam("backupId")?.takeIf { it.length in 1..64 } ?: throw BadRequest()

        authProvider.requirePermission(ManageServerBackupsPermission(), context, serverId)
        requireManage(context)

        // Coming soon: refused before anything is fetched (see MC_UPLOADS_ENABLED).
        if (!PanoRemoteBackupService.MC_UPLOADS_ENABLED) {
            throw PanoHostError.of(PanoHostException(PanoRemoteBackupService.NOT_AVAILABLE))
        }

        val remote = panoBackupManager.remote

        requireReady(remote, passphrase = false)

        val source = host { mcServerBackupSources.source(serverId, backupId, getSqlClient()) }

        return Successful(mapOf("job" to job { remote.startMcUpload(source) }.toJson()))
    }
}
