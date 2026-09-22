package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.backup.ManagedServerBackupService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Pins or unpins one backup (`{pinned: boolean}`).
 *
 * A pinned backup is never chosen by retention — not by a keep-last, not by the snapshot disk
 * cap — so "the world before the map reset" survives a year of nightly backups. No password:
 * unlike delete or restore nothing is destroyed here, and the worst a mistaken pin does is keep a
 * copy longer than intended.
 *
 * Works from Pano's own row alone, so a backup can be pinned while its node or plugin is offline;
 * nothing on the other side knows or needs to know about pins.
 */
@Endpoint
class PanelPinServerBackupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val backupService: ManagedServerBackupService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/:backupId/pin", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("backupId", stringSchema()))
            .body(json(objectSchema().requiredProperty("pinned", booleanSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val backupId = parameters.pathParameter("backupId").string

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val pinned = parameters.body().jsonObject.getBoolean("pinned") ?: false

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val backup = (databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: backupId.toLongOrNull()?.let { databaseManager.serverBackupDao.getById(it, sqlClient) })
            ?: throw NotExists()

        if (backup.serverId != id) {
            throw NotExists()
        }

        if (backup.pinned != pinned) {
            backupService.setPinned(server, backup, pinned, sqlClient)

            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

            databaseManager.panelActivityLogDao.add(
                ServerBackupActionLog(
                    userId,
                    username,
                    id,
                    if (pinned) ServerBackupActionLog.ACTION_PIN else ServerBackupActionLog.ACTION_UNPIN,
                    backup.uuid,
                    backup.name
                ),
                sqlClient
            )
        }

        return Successful(mapOf("backupId" to backup.uuid, "pinned" to pinned))
    }
}
