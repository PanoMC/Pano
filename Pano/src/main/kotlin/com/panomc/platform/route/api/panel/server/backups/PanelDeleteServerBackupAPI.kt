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
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
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
 * Removes one backup.
 *
 * Resolved from the server row rather than through the file client, so a backup can still be
 * deleted while its node is offline: the row goes and the archive is left as an orphan, which is
 * better than a list an operator cannot clean up until a machine comes back.
 */
@Endpoint
class PanelDeleteServerBackupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val backupService: ManagedServerBackupService,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/:backupId/delete", RouteType.POST))

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

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Whoever can take a backup owns the rest of them (§2.4.17): a node, or a plugin with `backups`.
        serverFeatureResolver.pick(server, ServerFeature.BACKUPS_CREATE)

        val backup = (databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: backupId.toLongOrNull()?.let { databaseManager.serverBackupDao.getById(it, sqlClient) })
            ?: throw NotExists()

        if (backup.serverId != id) {
            throw NotExists()
        }

        if (!backupService.delete(server, backup, sqlClient)) {
            throw FileOperationFailed(extras = mapOf("message" to "The node could not delete that backup."))
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerBackupActionLog(
                userId,
                username,
                id,
                ServerBackupActionLog.ACTION_DELETE,
                backup.uuid,
                backup.name
            ),
            sqlClient
        )

        return Successful()
    }
}
