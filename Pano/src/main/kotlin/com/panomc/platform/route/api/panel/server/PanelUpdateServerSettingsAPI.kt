package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.response.GetServerSettingsEventResponse
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.util.*

@Endpoint
class PanelUpdateServerSettingsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/settings", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("authIntegration", booleanSchema())
                        .requiredProperty("banIntegration", booleanSchema())
                        .requiredProperty("permissionIntegration", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val authIntegration = data.getBoolean("authIntegration")
        val banIntegration = data.getBoolean("banIntegration")
        val permissionIntegration = data.getBoolean("permissionIntegration")

        val sqlClient = getSqlClient()
        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotFound()

        val settings = server.settings

        settings.authIntegration = authIntegration
        settings.banIntegration = banIntegration
        settings.permissionIntegration = permissionIntegration

        databaseManager.serverDao.updateSettingsById(settings, id, sqlClient)

        val foundServer = serverManager.connectedServers.keys.find { it.id == id }

        if (foundServer != null) {
            foundServer.settings = settings

            val response = GetServerSettingsEventResponse(
                UUID.randomUUID(),
                settings.authIntegration,
                settings.banIntegration,
                settings.permissionIntegration
            )
            serverManager.sendMessage(response, foundServer)
        }

        return Successful()
    }
}