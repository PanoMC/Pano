package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetMySessionsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/sessions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

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
