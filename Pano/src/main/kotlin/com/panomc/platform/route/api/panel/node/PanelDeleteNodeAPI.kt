package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeRemovalService
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
import com.panomc.platform.util.UsageMode

/**
 * Deletes a node and everything on it (`POST /api/panel/nodes/:id/delete`, SM-64, §2.4.29 B).
 *
 * Body `{ currentPassword, force? }` → `{ removedFiles, serversDeleted, manualSteps: [] }`.
 *
 * It used to refuse while servers existed and never touched the host; now an online node removes
 * itself — servers, backups, Java runtimes, service — before Pano drops its servers and its row,
 * and the request waits for that (up to two minutes, progress over the usual `taskProgress` frames
 * as task kind `NODE_UNINSTALL`). A node that cannot is refused with `NODE_OFFLINE` or
 * `NODE_UNINSTALL_FAILED` (`nodeError`, `manualSteps`, `serverCount` in the body) unless `force`
 * is set, which removes it from Pano alone. The rules live in [NodeRemovalService].
 */
@Endpoint
class PanelDeleteNodeAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeRemovalService: NodeRemovalService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/nodes/:id/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .optionalProperty("force", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val body = parameters.body().jsonObject
        val currentPassword = body.getString("currentPassword")
        val force = body.getBoolean("force", false)

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, currentPassword, sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        val outcome = nodeRemovalService.delete(node, userId, username, force, sqlClient)

        return Successful(outcome.toMap())
    }
}
