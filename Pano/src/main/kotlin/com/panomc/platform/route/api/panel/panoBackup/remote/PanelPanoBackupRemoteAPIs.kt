package com.panomc.platform.route.api.panel.panoBackup.remote

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.backup.McServerBackupSources
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.LinkPurpose
import com.panomc.platform.backup.remote.PassphraseFile
import com.panomc.platform.backup.remote.RemoteBackupSettings
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.body
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.host
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.job
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.remoteId
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.requireReady
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/*
 * Panel routes of Pano Backup (the E2E-encrypted remote target on panomc.com) and of the transfer to
 * Pano Host. All need MANAGE_PANO_BACKUPS; the background jobs are polled with
 * `GET /api/panel/pano-backups/job`. Pano Host errors come back as `PANO_HOST_ERROR {hostError, …}`.
 */

/** Base: JSON bodies are read by hand (several paths/methods per class), permission checked first. */
abstract class PanoBackupRemoteApi(protected val authProvider: AuthProvider) : PanelApi() {
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    protected fun method(context: RoutingContext): HttpMethod = context.request().method()

    protected suspend fun requireManage(context: RoutingContext) = authProvider.requirePermission(ManagePanoBackupsPermission(), context)
}

/** `GET /api/panel/pano-backups/remote` → `{apiUrl, links, pending, settings, passphraseSet, lastUploadAt, job, busy}`. */
@Endpoint
class PanelGetPanoBackupRemoteAPI(authProvider: AuthProvider, private val panoBackupManager: PanoBackupManager) :
    PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/api/panel/pano-backups/remote", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val status = panoBackupManager.remote.status()
        val service = panoBackupManager.service

        status.put("apiUrl", panoBackupManager.hostApiUrl())
        status.put("job", service.job?.toJson())
        status.put("busy", service.isBusy())

        return Successful(status.map)
    }
}

/**
 * Device-code link: `POST …/remote/link {purpose BACKUP|TRANSFER}` → `{code, verifyUrl, expiresAt,
 * interval}` (open verifyUrl, approve on panomc.com); `POST …/remote/link/poll {purpose}` →
 * `{status NONE|PENDING|LINKED|EXPIRED, link?}`; `DELETE …/remote/link/:purpose` forgets the link here.
 */
@Endpoint
class PanelPanoBackupRemoteLinkAPI(authProvider: AuthProvider, private val panoBackupManager: PanoBackupManager) :
    PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/api/panel/pano-backups/remote/link", RouteType.POST),
        Path("/api/panel/pano-backups/remote/link/poll", RouteType.POST),
        Path("/api/panel/pano-backups/remote/link/:purpose", RouteType.DELETE)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val remote = panoBackupManager.remote

        if (method(context) == HttpMethod.DELETE) {
            remote.unlink(PanoBackupRemoteRoutes.purpose(context.pathParam("purpose")))

            return Successful()
        }

        val purpose = PanoBackupRemoteRoutes.purpose(body(context).getString("purpose"))

        if (context.normalizedPath().endsWith("/poll")) {
            return Successful(host { remote.pollLink(purpose) }.map)
        }

        return Successful(host { remote.startLink(purpose) }.toPublicJson().map)
    }
}

/**
 * `PUT …/remote/settings {schedule OFF|TIER|DAILY|WEEKLY, hour, mcServerIds}`;
 * `PUT …/remote/passphrase {currentPassword, passphrase}` (≥ 8 characters; kept on this server only,
 * lost = the remote backups are unrecoverable; changing it does not re-encrypt older backups).
 */
@Endpoint
class PanelUpdatePanoBackupRemoteAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/api/panel/pano-backups/remote/settings", RouteType.PUT),
        Path("/api/panel/pano-backups/remote/passphrase", RouteType.PUT)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val body = body(context)
        val remote = panoBackupManager.remote

        if (context.normalizedPath().endsWith("/settings")) {
            val settings = RemoteBackupSettings.fromJson(body) ?: throw BadRequest()

            remote.saveSettings(settings)

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

        return Successful(mapOf("passphraseSet" to true))
    }
}

/**
 * `GET …/remote/backups` → `{backups, tier, usage}` (the account's Pano Backups; `own` = this link's);
 * `POST …/remote/backups` → `{job}` (archive with the saved passphrase + upload).
 */
@Endpoint
class PanelPanoBackupRemoteBackupsAPI(authProvider: AuthProvider, private val panoBackupManager: PanoBackupManager) :
    PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/api/panel/pano-backups/remote/backups", RouteType.ROUTE))

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val remote = panoBackupManager.remote

        return when (method(context)) {
            HttpMethod.GET -> Successful(host { remote.listBackups() }.map)
            HttpMethod.POST -> {
                requireReady(remote, LinkPurpose.BACKUP, passphrase = true)

                Successful(mapOf("job" to job { remote.startUpload() }.toJson()))
            }

            else -> throw BadRequest()
        }
    }
}

/**
 * `POST …/remote/backups/:id/restore {currentPassword, passphrase?}` → `{job}` (download, verify,
 * then the local restore flow: maintenance, safety archive, restart; passphrase omitted = the saved
 * one); `DELETE …/remote/backups/:id` (this link's own backups only).
 */
@Endpoint
class PanelPanoBackupRemoteBackupAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/api/panel/pano-backups/remote/backups/:id/restore", RouteType.POST),
        Path("/api/panel/pano-backups/remote/backups/:id", RouteType.DELETE)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val id = remoteId(context)
        val remote = panoBackupManager.remote

        if (method(context) == HttpMethod.DELETE) {
            host { remote.deleteBackup(id) }

            return Successful()
        }

        val body = body(context)
        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, body.getString("currentPassword") ?: "", getSqlClient())) {
            throw CurrentPasswordNotCorrect()
        }

        requireReady(remote, LinkPurpose.BACKUP, passphrase = false)

        val passphrase = PanoBackupRoutes.passphrase(body.getString("passphrase"))

        return Successful(mapOf("job" to job { remote.startRestore(id, passphrase) }.toJson()))
    }
}

/**
 * Transfer to Pano Host through a TRANSFER link: `GET …/remote/transfers` → `{workload, transfers}`;
 * `POST …/remote/transfers` → `{job}` (plain archive pushed; then the owner confirms on panomc.com);
 * `GET …/remote/transfers/:id` → `{transfer}`; `DELETE …/remote/transfers/:id` cancels a pending one.
 */
@Endpoint
class PanelPanoBackupRemoteTransfersAPI(authProvider: AuthProvider, private val panoBackupManager: PanoBackupManager) :
    PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(
        Path("/api/panel/pano-backups/remote/transfers", RouteType.ROUTE),
        Path("/api/panel/pano-backups/remote/transfers/:id", RouteType.ROUTE)
    )

    override suspend fun handle(context: RoutingContext): Result {
        requireManage(context)

        val remote = panoBackupManager.remote
        val single = context.pathParam("id") != null

        return when (method(context)) {
            HttpMethod.GET -> if (single) {
                Successful(mapOf("transfer" to host { remote.getTransfer(remoteId(context)) }))
            } else {
                Successful(host { remote.listTransfers() }.map)
            }

            HttpMethod.POST -> {
                if (single) throw BadRequest()

                requireReady(remote, LinkPurpose.TRANSFER, passphrase = false)

                Successful(mapOf("job" to job { remote.startTransfer() }.toJson()))
            }

            HttpMethod.DELETE -> {
                if (!single) throw BadRequest()

                host { remote.cancelTransfer(remoteId(context)) }

                Successful()
            }

            else -> throw BadRequest()
        }
    }
}

/** `POST /api/panel/servers/:id/backups/:backupId/pano-backup` → `{job}`: one MC server backup to Pano Backup. */
@Endpoint
class PanelUploadServerBackupToPanoBackupAPI(
    authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager,
    private val mcServerBackupSources: McServerBackupSources
) : PanoBackupRemoteApi(authProvider) {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/:backupId/pano-backup", RouteType.POST))

    override suspend fun handle(context: RoutingContext): Result {
        val serverId = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()
        val backupId = context.pathParam("backupId")?.takeIf { it.length in 1..64 } ?: throw BadRequest()

        authProvider.requirePermission(ManageServerBackupsPermission(), context, serverId)
        requireManage(context)

        val remote = panoBackupManager.remote

        requireReady(remote, LinkPurpose.BACKUP, passphrase = true)

        val source = host { mcServerBackupSources.source(serverId, backupId, getSqlClient()) }

        return Successful(mapOf("job" to job { remote.startMcUpload(source) }.toJson()))
    }
}
