package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.FileOperationFailed
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Puts a backup back over a managed server.
 *
 * The most destructive thing in the file manager: it overwrites a live world with an old one, so
 * it costs a password like deleting a server does, and it is refused unless the server is already
 * stopped. The node refuses it again for itself — it is the side that knows whether a process is
 * actually holding those files — but refusing here means the person gets a sentence instead of a
 * failed task.
 */
@Endpoint
class PanelRestoreServerBackupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val backupService: ManagedServerBackupService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/:backupId/restore", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("backupId", stringSchema()))
            .body(json(objectSchema().requiredProperty("currentPassword", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val backupId = parameters.pathParameter("backupId").string

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val currentPassword = parameters.body().jsonObject.getString("currentPassword")

        val sqlClient = getSqlClient()

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, currentPassword, sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val target = fileClient.resolve(id, sqlClient, ServerFeature.BACKUPS_RESTORE)

        val backup = (databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: backupId.toLongOrNull()?.let { databaseManager.serverBackupDao.getById(it, sqlClient) })
            ?: throw NotExists()

        if (backup.serverId != id) {
            throw NotExists()
        }

        if (backup.status != ServerBackupStatus.READY) {
            throw FileOperationFailed(extras = mapOf("message" to "That backup was never finished."))
        }

        // Only the node overwrites files in place, and only a stopped process lets it. The
        // plugin path is the opposite case by definition -- it answers from inside a running
        // server -- and arms the restore for the next start instead (§2.4.17 C).
        if (target.source == ServerFeatureSource.NODE && !isStopped(target.server.processState)) {
            throw FileOperationFailed(extras = mapOf("message" to "Stop the server before restoring a backup."))
        }

        val task = backupService.restore(target, backup, userId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerBackupActionLog(
                userId,
                username,
                id,
                ServerBackupActionLog.ACTION_RESTORE,
                backup.uuid,
                backup.name
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "taskId" to task.id,
                "taskUuid" to task.uuid,
                "source" to target.source.id,
                // The panel says "applies on the next start" off this rather than guessing from
                // the source, because a node restore that had to wait would say the same thing.
                "mode" to if (task.status == ServerTaskStatus.PENDING_RESTART) {
                    ManagedServerBackupService.MODE_NEXT_START
                } else {
                    MODE_LIVE
                }
            )
        )
    }

    companion object {
        /** A restore that happened there and then, which is the node's way. */
        const val MODE_LIVE = "live"

        /**
         * Whether a restore may start from this process state.
         *
         * A crashed server is stopped too — nothing is holding its files — and a row with no state
         * at all is one no node has reported on yet, which is the same thing.
         */
        fun isStopped(state: ServerProcessState?): Boolean =
            state == null || state == ServerProcessState.STOPPED || state == ServerProcessState.CRASHED
    }
}
