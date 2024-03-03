package com.panomc.platform.route.api.panel.server


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetServerDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/dashboard", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_SERVERS, context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val result = mutableMapOf<String, Any?>(
            "server" to null,
            "connectedServerCount" to 0
        )

        val sqlClient = getSqlClient()

        result["server"] = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        result["connectedServerCount"] = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

        return Successful(result)
    }
}