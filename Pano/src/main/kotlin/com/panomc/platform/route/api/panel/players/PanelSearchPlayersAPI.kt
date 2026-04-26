package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelSearchPlayersAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/player/search", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("q", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        // This endpoint is used by the permissions editor, so align with its permission.
        authProvider.requirePermission(ManagePermissionGroupsPermission(), context)

        val parameters = getParameters(context)
        val q = parameters.queryParameter("q")?.string?.trim().orEmpty()

        if (q.isEmpty()) {
            return Successful(mapOf("players" to emptyList<Any>()))
        }

        val sqlClient = databaseManager.getSqlClient()
        val hits = databaseManager.userDao.searchIdsAndUsernamesByUsername(q, 10, sqlClient)

        val players = hits.map { (userId, username, registeredIp) ->
            mapOf(
                "id" to userId,
                "username" to username,
                "registeredIp" to registeredIp,
            )
        }

        return Successful(mapOf("players" to players))
    }
}


