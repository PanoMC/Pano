package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.RemovedNodeJavaLog
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
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Removes a managed Java runtime from a node (`POST /api/panel/nodes/:id/java/:major/delete`,
 * SM-63).
 *
 * The body is optional: `{ "version": "21.0.12+7" }` picks one of several managed runtimes of the
 * same major, and without it the node removes the managed runtime of that major. Whether it may —
 * a server running from it, a server pinned to the major with nothing else left to run it, a
 * runtime the node did not download — is the node's call, since only the node can see its
 * processes; a refusal comes back as a FAILED task with `JAVA_IN_USE`, `NOT_MANAGED` or
 * `NOT_FOUND` in its `error`.
 *
 * No `currentPassword`: this deletes a runtime the node can download again in a minute, not
 * anybody's data.
 */
@Endpoint
class PanelDeleteNodeJavaAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeJavaTaskService: NodeJavaTaskService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/nodes/:id/java/:major/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("major", intSchema()))
            .body(json(objectSchema().optionalProperty("version", stringSchema().nullable())))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val major = parameters.pathParameter("major").integer
        val version = parameters.body()?.jsonObject?.getString("version")?.trim()?.takeIf { it.isNotEmpty() }

        if (!NodeJavaCatalog.isValidMajor(major) || (version != null && !NodeJavaCatalog.isValidVersion(version))) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        nodeJavaTaskService.requireCapable(node)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // Shares the install window: both are a task on the node, and a script flipping one major
        // between installed and removed is the loop this is for.
        if (!serverActionRateLimiter.tryAcquireForUser(ServerActionRateLimiter.Action.JAVA_RUNTIME, userId)) {
            throw RateLimited()
        }

        val task = nodeJavaTaskService.remove(node, major, version, userId, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            RemovedNodeJavaLog(userId, username, id, node.name, major, version),
            sqlClient
        )

        return Successful(mapOf("taskId" to task.id, "taskUuid" to task.uuid))
    }
}
