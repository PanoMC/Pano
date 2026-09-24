package com.panomc.platform.route.api.panel.server.power

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPowerActionLog
import com.panomc.platform.auth.panel.permission.ManageServerPowerPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.error.ServerOffline
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.PowerMessage as NodePowerMessage
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerPowerAction
import com.panomc.platform.server.message.PowerMessage
import com.panomc.platform.server.console.ServerActionRateLimiter
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
import java.util.UUID
import com.panomc.platform.util.UsageMode

/**
 * Changes a server's power state.
 *
 * Which route the request takes depends on who owns the process. For a managed server it goes to
 * the node, which really can start a stopped server and kill a hung one. For a linked server it
 * goes to the plugin, which can only ask its own platform to shut down — so START and KILL are
 * rejected there rather than silently downgraded into something else.
 */
@Endpoint
class PanelServerPowerActionAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/power", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("action", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPowerPermission(), context, id)

        // §2.4.12. Taking a server down affects everyone on it, so a stuck button or a loop must not be able to do it six times a minute.
        if (!serverActionRateLimiter.tryAcquire(
                ServerActionRateLimiter.Action.POWER,
                authProvider.getUserIdFromRoutingContext(context),
                id
            )
        ) {
            throw RateLimited()
        }

        val action = ServerPowerAction.fromId(parameters.body().jsonObject.getString("action")) ?: throw BadRequest()

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        if (server.isManaged) {
            sendToNode(server, action, username)
        } else {
            sendToPlugin(server, action, username)
        }

        databaseManager.panelActivityLogDao.add(
            ServerPowerActionLog(userId, username, id, action.name),
            sqlClient
        )

        return Successful()
    }

    private fun sendToNode(server: Server, action: ServerPowerAction, username: String) {
        val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
        val uuid = server.uuid ?: throw ServerCapabilityMissing()

        if (!nodeManager.isConnected(nodeId)) {
            throw NodeOffline()
        }

        val sent = nodeManager.sendMessage(
            nodeId,
            NodePowerMessage(
                serverUuid = uuid,
                action = action.name,
                requestId = UUID.randomUUID().toString(),
                issuedBy = username
            )
        )

        if (!sent) {
            throw NodeOffline()
        }
    }

    private fun sendToPlugin(server: Server, action: ServerPowerAction, username: String) {
        // Nothing on this side owns a linked server's process: there is no handle to start it with
        // and no way to kill it, because the request would have to travel through the very process
        // being killed.
        if (!action.isSupportedByPlugin) {
            throw ServerCapabilityMissing()
        }

        if (!server.hasCapability(ServerCapability.POWER)) {
            throw ServerCapabilityMissing()
        }

        if (!serverManager.isConnected(server.id)) {
            throw ServerOffline()
        }

        val sent = serverManager.sendMessage(
            server.id,
            PowerMessage(
                action = action.name,
                requestId = UUID.randomUUID().toString(),
                issuedBy = username
            )
        )

        if (!sent) {
            throw ServerOffline()
        }
    }
}
