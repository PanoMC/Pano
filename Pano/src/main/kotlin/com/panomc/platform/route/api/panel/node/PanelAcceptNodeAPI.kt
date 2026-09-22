package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.AcceptedNodeLog
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Lets a node that paired with the rotating code into this Pano.
 *
 * Nothing is pushed to the node: it cannot be connected yet, because [NodeConnectAPI] refuses an
 * unapproved node. Its own reconnect loop is what picks the approval up, usually within seconds.
 */
@Endpoint
class PanelAcceptNodeAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id/accept", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        databaseManager.nodeDao.updateApprovedById(id, true, System.currentTimeMillis(), sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(AcceptedNodeLog(userId, username, id, node.name), sqlClient)

        panelRealtimeHub.notifyNodeUpdated(id)

        return Successful()
    }
}
