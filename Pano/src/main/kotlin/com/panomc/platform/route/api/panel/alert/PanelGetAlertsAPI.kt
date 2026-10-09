package com.panomc.platform.route.api.panel.alert

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerAlert
import com.panomc.platform.model.*
import com.panomc.platform.model.CursorPaging
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * The most recent alerts, newest first, a page at a time: `limit` and `cursor` in, `{ items, page: { size,
 * nextCursor } }` out.
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
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/alerts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        CursorPaging.params(ValidationHandlerBuilder.create(schemaRepository))
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

        val limit = CursorPaging.limit(context, DEFAULT_LIMIT, MAX_LIMIT)

        val before = CursorPaging.idCursor(context)

        val sqlClient = getSqlClient()

        // One row more than the page, to know whether an older page exists without a count.
        val (alerts, nextCursor) = CursorPaging.split(
            if (serverId == null) {
                databaseManager.serverAlertDao.getLatest(limit + 1, before, sqlClient)
            } else {
                databaseManager.serverAlertDao.getLatestByServerId(serverId, limit + 1, before, sqlClient)
            },
            limit
        ) { it.id }

        // Names resolved once per distinct id rather than once per row: a burst of alerts about
        // one server is the common case, and it should not be one query each.
        val serverNames = alerts.mapNotNull { it.serverId }.distinct().associateWith { id ->
            databaseManager.serverDao.getById(id, sqlClient)?.let { it.customName ?: it.name }
        }

        val nodeNames = alerts.mapNotNull { it.nodeId }.distinct().associateWith { id ->
            databaseManager.nodeDao.getById(id, sqlClient)?.name
        }

        return Successful(payload(alerts, serverNames, nodeNames, limit, nextCursor))
    }

    companion object {
        /** The whole response body: `{ items, page: { size, nextCursor } }`, each row with its server and node name. */
        fun payload(
            alerts: List<ServerAlert>,
            serverNames: Map<Long, String?>,
            nodeNames: Map<Long, String?>,
            limit: Int,
            nextCursor: String?
        ): Map<String, Any?> = CursorPaging.response(
            alerts.map { alert ->
                alert.toPublicJsonObject()
                    .put("serverName", alert.serverId?.let { serverNames[it] })
                    .put("nodeName", alert.nodeId?.let { nodeNames[it] })
            },
            limit,
            nextCursor
        )

        private const val DEFAULT_LIMIT = 50

        /** Enough to fill a page of history without letting one request read the whole table. */
        private const val MAX_LIMIT = 200
    }
}
