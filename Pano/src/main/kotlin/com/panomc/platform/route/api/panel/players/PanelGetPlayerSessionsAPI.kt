package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Path
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetPlayerSessionsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/sessions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)
        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()
        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotExists()

        val tokens = databaseManager.tokenDao.getAllBySubjectAndType(userId.toString(), TokenType.AUTHENTICATION, sqlClient)

        val currentToken = authProvider.getTokenFromRoutingContext(context)

        val sessions = tokens.map {
            mapOf(
                "id" to it.id,
                "ip" to (it.ipAddress ?: "-"), 
                "userAgent" to (it.userAgent ?: "-"),
                "lastActivityTime" to it.startDate,
                "expireDate" to it.expireDate,
                "isCurrent" to (it.token == currentToken)
            )
        }

        return Successful(mapOf("sessions" to sessions))
    }
}
