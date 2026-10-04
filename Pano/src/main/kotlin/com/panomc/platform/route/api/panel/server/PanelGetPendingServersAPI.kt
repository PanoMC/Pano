package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Servers that have asked to connect and are still waiting to be approved
 * (`GET /api/panel/servers/pending`).
 *
 * Separate from `GET /api/panel/servers`, which lists only approved ones: a pending server is not
 * something the panel can select, show a console for or send a command to, it is a decision an
 * admin has to make, and mixing the two lists is how a server nobody approved ends up looking
 * connected.
 *
 * Registered at route order 0, like `nodes/pairing-code`, because `/api/panel/servers/:id` would
 * otherwise match `pending` first and reject it as a non-numeric id -- Vert.x fails the first
 * matching route instead of trying the next one.
 */
@Endpoint
class PanelGetPendingServersAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val order = 0

    override val paths = listOf(Path("/api/panel/servers/pending", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val sqlClient = getSqlClient()

        // The public view, which is the whole reason this does not just serialize the rows: a
        // pending server's aesKey is the secret its plugin connection is encrypted with, and it
        // exists on the row before anyone has decided to trust that server at all.
        val pending = databaseManager.serverDao.getAllPending(sqlClient).map { it.toPublicJsonObject() }

        return Successful(
            mapOf(
                "servers" to JsonArray(pending),
                "count" to pending.size
            )
        )
    }
}
