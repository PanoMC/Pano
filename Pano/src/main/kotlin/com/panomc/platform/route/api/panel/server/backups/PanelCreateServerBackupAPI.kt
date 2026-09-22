package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.backup.BackupOptions
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.console.ServerActionRateLimiter
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Starts a backup of a managed server.
 *
 * Returns immediately with the ids to follow: the archive takes as long as the server directory is
 * big, and the panel watches `taskProgress` for the rest.
 *
 * Body (all optional): `name`, `mode` (`FULL` | `SNAPSHOT`), `scope` (`ALL` | `WORLDS` |
 * `CUSTOM`), `include` (roots, required and non-empty for `CUSTOM`), `exclude` (the operator's
 * extras) and `excludeDefaults` (default true: `logs/`, `cache/` and `*.jar.tmp` go in front of the
 * extras). The node or plugin is always sent the full list, see [BackupOptions.effectiveExclude].
 */
@Endpoint
class PanelCreateServerBackupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val backupService: ManagedServerBackupService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("name", stringSchema())
                        .optionalProperty("mode", stringSchema())
                        .optionalProperty("scope", stringSchema())
                        .optionalProperty("include", arraySchema().items(stringSchema()))
                        .optionalProperty("exclude", arraySchema().items(stringSchema()))
                        .optionalProperty("excludeDefaults", booleanSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val body = parameters.body()?.jsonObject

        val name = body?.getString("name")

        // Checked before the rate limiter spends a slot: a typo in the include list should not
        // cost one of the three backups in ten minutes.
        val options = BackupOptions.parse(body)

        // §2.4.12. A backup copies an entire world; three in ten minutes is already generous.
        if (!serverActionRateLimiter.tryAcquire(
                ServerActionRateLimiter.Action.BACKUP,
                authProvider.getUserIdFromRoutingContext(context),
                id
            )
        ) {
            throw RateLimited()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient, ServerFeature.BACKUPS_CREATE)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val (backup, task) = backupService.create(target, name, userId, sqlClient, options)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerBackupActionLog(
                userId,
                username,
                id,
                ServerBackupActionLog.ACTION_CREATE,
                backup.uuid,
                backup.name
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "backupId" to backup.uuid,
                "taskId" to task.id,
                "taskUuid" to task.uuid
            )
        )
    }
}
