package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeDaemonUpdateService
import com.panomc.platform.route.api.panel.node.PanelUpdateNodeDaemonAPI
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * Updates the Pano Agent behind a server (`POST /api/panel/servers/:id/agent/update`).
 *
 * An agent is a node, and it is updated exactly like one -- `SELF_UPDATE` with the jar this Pano
 * serves, through [NodeDaemonUpdateService] -- but it is never shown on the nodes page, so the
 * manual update has to be reachable from its server, under the servers permission. The automatic
 * update (`managed-servers.node-auto-update`) covers agents as well.
 *
 * Answers like `POST /api/panel/nodes/:id/update`: `{ upToDate: true }`, or `{ version, sha256 }`
 * of what was offered; 404 when this Pano has no daemon jar to hand out, `NODE_OFFLINE` when the
 * agent is not connected, and `SERVER_CAPABILITY_MISSING` for a server that has no agent.
 */
@Endpoint
class PanelUpdateServerAgentAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeDaemonUpdateService: NodeDaemonUpdateService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/agent/update", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val id = getParameters(context).pathParameter("id").long

        // Scoped to this server: whoever may manage it may update the agent that runs it.
        authProvider.requirePermission(ManageServersPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        val nodeId = server.nodeId?.takeIf { server.isManaged } ?: throw ServerCapabilityMissing()

        val node = databaseManager.nodeDao.getById(nodeId, sqlClient) ?: throw ServerCapabilityMissing()

        if (!node.agent) {
            throw ServerCapabilityMissing()
        }

        return PanelUpdateNodeDaemonAPI.respond(nodeDaemonUpdateService.update(node.id))
    }
}
