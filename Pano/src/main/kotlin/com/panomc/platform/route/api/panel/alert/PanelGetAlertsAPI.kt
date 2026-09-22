package com.panomc.platform.route.api.panel.alert

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * The most recent alerts, newest first.
 *
 * The durable half of alerting: notifications get read and dismissed, and this is what is left to
 * look at afterwards. Rows carry the server and node names resolved so the panel can render a list
 * without a lookup per row.
 */
@Endpoint
class PanelGetAlertsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/alerts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("limit", numberSchema()))
            .queryParameter(optionalParam("serverId", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val serverId = parameters.queryParameter("serverId")?.long

        if (serverId == null) {
            authProvider.requirePermission(ManageServersPermission(), context)
        } else {
            authProvider.requirePermission(ManageServersPermission(), context, serverId)
        }

        val limit = (parameters.queryParameter("limit")?.integer ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val sqlClient = getSqlClient()

        val alerts = if (serverId == null) {
            databaseManager.serverAlertDao.getLatest(limit, sqlClient)
        } else {
            databaseManager.serverAlertDao.getLatestByServerId(serverId, limit, sqlClient)
        }

        // Names resolved once per distinct id rather than once per row: a burst of alerts about
        // one server is the common case, and it should not be one query each.
        val serverNames = alerts.mapNotNull { it.serverId }.distinct().associateWith { id ->
            databaseManager.serverDao.getById(id, sqlClient)?.let { it.customName ?: it.name }
        }

        val nodeNames = alerts.mapNotNull { it.nodeId }.distinct().associateWith { id ->
            databaseManager.nodeDao.getById(id, sqlClient)?.name
        }

        return Successful(
            mapOf(
                "alerts" to JsonArray(
                    alerts.map { alert ->
                        alert.toPublicJsonObject()
                            .put("serverName", alert.serverId?.let { serverNames[it] })
                            .put("nodeName", alert.nodeId?.let { nodeNames[it] })
                    }
                )
            )
        )
    }

    companion object {
        private const val DEFAULT_LIMIT = 50

        /** Enough to fill a page of history without letting one request read the whole table. */
        private const val MAX_LIMIT = 200
    }
}
