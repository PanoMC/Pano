package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.InstalledNodeJavaLog
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeJavaCatalog
import com.panomc.platform.node.NodeJavaTaskService
import com.panomc.platform.server.console.ServerActionRateLimiter
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Downloads (or updates) one Java major on a node (`POST /api/panel/nodes/:id/java`, SM-63).
 *
 * The same request is the card's Install and its Update: the node installs the newest build of
 * [major] it can find, and ends DONE at once when the managed one it has is already that build.
 * Answers with the task to follow; the runtime shows up in the card when the node sends its new
 * list (`NODE_JAVA_RUNTIMES`).
 */
@Endpoint
class PanelInstallNodeJavaAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeJavaTaskService: NodeJavaTaskService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id/java", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(json(objectSchema().requiredProperty("major", intSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val major = parameters.body().jsonObject.getInteger("major")

        if (!NodeJavaCatalog.isValidMajor(major)) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        // Before the rate limiter spends a slot: pressing Install on an offline node is not a
        // download.
        nodeJavaTaskService.requireCapable(node)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquireForUser(ServerActionRateLimiter.Action.JAVA_RUNTIME, userId)) {
            throw RateLimited()
        }

        val task = nodeJavaTaskService.install(node, major, userId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(InstalledNodeJavaLog(userId, username, id, node.name, major), sqlClient)

        return Successful(mapOf("taskId" to task.id, "taskUuid" to task.uuid))
    }
}
