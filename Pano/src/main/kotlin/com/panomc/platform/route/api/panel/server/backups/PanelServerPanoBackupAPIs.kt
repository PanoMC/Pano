package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.PanoBackupActionLog
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.backup.McServerBackupSources
import com.panomc.platform.backup.PanoBackupAudit
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.PanoHostError
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteApi
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.body
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.job
import com.panomc.platform.route.api.panel.panoBackup.remote.PanoBackupRemoteRoutes.requireReady
import com.panomc.platform.util.UsageMode
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.PanoBackupRetention
import com.panomc.platform.server.backup.ServerPanoBackupSettings
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

/*
 * One managed server's side of Pano Backup (SM-PB): whether its backups go to the connected account, how long they are
 * kept there, and bringing one back into a server. Need MANAGE_SERVER_BACKUPS on that server and MANAGE_PANO_BACKUPS.
 */

/**
 * `GET /api/v1/panel/servers/:id/backups/pano-backup` → `{settings {enabled, mode, retention, keepLast, keepDays},
 * connected, passphraseSet, usage, backups[], hostError?}` (`backups` = this Pano's uploads of this server, archives
 * and snapshots, each with its `type`, newest first; empty while not connected); `PUT …` with any of the settings' fields → `{settings}`.
 */
@Endpoint
class PanelServerPanoBackupAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/:id/backups/pano-backup", RouteType.ROUTE))

    override suspend fun handle(context: RoutingContext): Result {
        val serverId = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerBackupsPermission(), context, serverId)
        requireManage(context)

        val sqlClient = getSqlClient()
        val server = databaseManager.serverDao.getById(serverId, sqlClient)?.takeIf { it.permissionGranted } ?: throw NotExists()
        val remote = panoBackupManager.remote

        return when (method(context)) {
            HttpMethod.GET -> Successful(overview(server, remote).map)

            HttpMethod.PUT -> {
                val settings = read(body(context), server.settings.panoBackup.copy())

                server.settings.panoBackup = settings
                databaseManager.serverDao.updateSettingsById(server.settings, serverId, sqlClient)

                panoBackupAudit.log(context, PanoBackupActionLog.ACTION_REMOTE_SETTINGS, "server:$serverId")

                Successful(mapOf("settings" to settings.toMap()))
            }

            else -> throw BadRequest()
        }
    }

    private suspend fun overview(server: Server, remote: PanoRemoteBackupService): JsonObject {
        val status = remote.status()
        val result = JsonObject()
            .put("settings", JsonObject(server.settings.panoBackup.toMap()))
            .put("connected", status.getBoolean("connected", false))
            .put("passphraseSet", status.getBoolean("passphraseSet", false))
            .put("usage", status.getJsonObject("usage"))
            .put("backups", io.vertx.core.json.JsonArray())

        status.getJsonObject("hostError")?.let { result.put("hostError", it) }

        val subject = server.uuid

        if (!result.getBoolean("connected") || subject == null) {
            return result
        }

        try {
            result.put("backups", io.vertx.core.json.JsonArray(remote.listMcBackups(subject)))
        } catch (e: PanoHostException) {
            result.put("hostError", JsonObject().put("code", e.code).mergeIn(e.extras))
        }

        return result
    }

    /** [current] changed by the fields [body] holds; anything out of range is refused. */
    private fun read(body: JsonObject, current: ServerPanoBackupSettings): ServerPanoBackupSettings {
        if (body.isEmpty) throw BadRequest()

        body.getValue("enabled")?.let { current.enabled = it as? Boolean ?: throw BadRequest() }
        body.getValue("mode")?.let { current.mode = BackupMode.fromId(it as? String) ?: throw BadRequest() }
        body.getValue("retention")?.let { current.retention = PanoBackupRetention.fromId(it as? String) ?: throw BadRequest() }
        body.getValue("keepLast")?.let { current.keepLast = limit(it, ServerPanoBackupSettings.MAX_KEEP_LAST) }
        body.getValue("keepDays")?.let { current.keepDays = limit(it, ServerPanoBackupSettings.MAX_KEEP_DAYS) }

        return current
    }

    private fun limit(value: Any, max: Int): Int {
        val number = (value as? Number)?.toInt() ?: throw BadRequest()

        if (number < 1 || number > max) throw BadRequest(extras = mapOf("max" to max))

        return number
    }
}

/**
 * `POST /api/v1/panel/servers/:id/backups/pano-backup/import {remoteId, type? archive|snapshot, passphrase?, restore?,
 * currentPassword?}` → `{job}`: an `mc-server` archive of the account (any server's, any Pano's), or a server snapshot of
 * this Pano, is brought into this server's own backups. `restore: true` (with the account's `currentPassword`, on a
 * stopped server: `SERVER_NOT_STOPPED`) then restores the server from it, the ordinary restore. `passphrase` omitted =
 * the saved one (an archive's only: snapshots are sealed with the repository key).
 */
@Endpoint
class PanelImportServerPanoBackupAPI(
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager,
    private val mcServerBackupSources: McServerBackupSources,
    private val panoBackupAudit: PanoBackupAudit
) : PanoBackupRemoteApi(authProvider) {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/:id/backups/pano-backup/import", RouteType.POST))

    override suspend fun handle(context: RoutingContext): Result {
        val serverId = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerBackupsPermission(), context, serverId)
        requireManage(context)

        val body = body(context)
        val remoteId = body.getString("remoteId")?.takeIf { it.length in 1..64 } ?: throw BadRequest()
        val snapshot = when (body.getString("type") ?: PanoRemoteBackupService.TYPE_ARCHIVE) {
            PanoRemoteBackupService.TYPE_ARCHIVE -> false
            PanoRemoteBackupService.TYPE_SNAPSHOT -> true
            else -> throw BadRequest()
        }
        val passphrase = PanoBackupRoutes.passphrase(body.getString("passphrase"))
        val restore = body.getBoolean("restore") == true
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val remote = panoBackupManager.remote

        requireReady(remote, passphrase = false)

        // Restoring overwrites the server: the account password, like the server's own restore, and a stopped server.
        if (restore) {
            val currentPassword = body.getString("currentPassword") ?: throw CurrentPasswordNotCorrect()

            if (!databaseManager.userDao.isPasswordCorrectWithId(userId, currentPassword, getSqlClient())) {
                throw CurrentPasswordNotCorrect()
            }

            if (!mcServerBackupSources.canRestoreNow(serverId)) {
                throw PanoHostError.of(PanoHostException(McServerBackupSources.SERVER_NOT_STOPPED))
            }
        }

        val importer = mcServerBackupSources.importer(userId, remoteId, restore)
        val job = job { remote.startMcImport(remoteId, passphrase, serverId, importer, snapshot, restore) }

        panoBackupAudit.log(context, PanoBackupActionLog.ACTION_RESTORE_REMOTE, remoteId)

        return Successful(mapOf("job" to job.toJson()))
    }
}
