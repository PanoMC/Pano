package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.token.AuthenticationTokenType
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

        if (com.panomc.platform.Main.IS_DEMO) {
            val sessions = (1..5).map {
                mapOf(
                    "id" to it,
                    "ip" to "127.0.0.1",
                    "userAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "lastActivityTime" to System.currentTimeMillis() - (it * 3600000),
                    "expireDate" to System.currentTimeMillis() + (30L * 24 * 3600000),
                    "isCurrent" to (it == 1)
                )
            }
            return Successful(mapOf("sessions" to sessions))
        }

        val tokens = databaseManager.tokenDao.getAllBySubjectAndType(userId.toString(), AuthenticationTokenType, sqlClient)

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
