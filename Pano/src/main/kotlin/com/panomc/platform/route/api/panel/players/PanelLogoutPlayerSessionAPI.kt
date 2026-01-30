package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait

@Endpoint
class PanelLogoutPlayerSessionAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/sessions/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("username", stringSchema()))
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val token = databaseManager.tokenDao.getById(id, sqlClient)

        if (token != null) {
            databaseManager.tokenDao.deleteById(id, sqlClient)

            val currentToken = authProvider.getTokenFromRoutingContext(context) // Administrator's token
            // If the administrator is deleting their own session (which is allowed), clear cookies
            if (token.token == currentToken) {
                authProvider.clearCookies(context)
            }
        }

        return Successful()
    }
}
