package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.FileOperationFailed
import com.panomc.platform.error.TransferExpired
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.TransferPullMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams a backup archive to the browser.
 *
 * The same ticket mechanism as a file download, with a path the node recognises as "not a file
 * inside the server": backups live outside the server directory precisely so a wipe cannot take
 * them, which also puts them outside every path rule, so `@backup/<id>` is the one door to them.
 */
@Endpoint
class PanelDownloadServerBackupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/:backupId/download", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("backupId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val backupId = parameters.pathParameter("backupId").string

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val backup = (databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: backupId.toLongOrNull()?.let { databaseManager.serverBackupDao.getById(it, sqlClient) })
            ?: throw NotExists()

        if (backup.serverId != id || backup.status != ServerBackupStatus.READY) {
            throw NotExists()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val path = VIRTUAL_PREFIX + backup.uuid

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = id,
            nodeId = target.nodeId,
            serverUuid = target.serverUuid,
            path = path,
            direction = TransferDirection.DOWNLOAD,
            fileName = "${backup.name}.zip",
            browserResponse = context.response()
        )

        fileClient.send(target, TransferPullMessage(ticket.id, target.serverUuid, path))

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerBackupActionLog(
                userId,
                username,
                id,
                ServerBackupActionLog.ACTION_DOWNLOAD,
                backup.uuid,
                backup.name
            ),
            sqlClient
        )

        val written = withTimeoutOrNull(TransferTicketStore.TTL_MS) {
            try {
                ticket.completion.await()
            } catch (exception: Exception) {
                transferTicketStore.discard(ticket)

                throw FileOperationFailed(extras = mapOf("message" to (exception.message ?: "The transfer failed.")))
            }
        }

        transferTicketStore.discard(ticket)

        if (written == null) {
            throw TransferExpired()
        }

        return null
    }

    companion object {
        /** Matches the node's `BackupService.VIRTUAL_PREFIX`; changing one changes the protocol. */
        const val VIRTUAL_PREFIX = "@backup/"
    }
}
