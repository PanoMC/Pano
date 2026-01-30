package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class LogoutMySessionAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/sessions/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        // Verify the token belongs to the user to prevent deleting other users' sessions
        val token = databaseManager.tokenDao.getById(id, sqlClient)

        if (token != null && token.subject == userId.toString()) {
            databaseManager.tokenDao.deleteById(id, sqlClient)

            val currentToken = authProvider.getTokenFromRoutingContext(context)
            if (token.token == currentToken) {
                authProvider.clearCookies(context)
            }
        }

        return Successful()
    }
}
