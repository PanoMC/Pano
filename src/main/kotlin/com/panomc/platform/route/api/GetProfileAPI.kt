package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetProfileAPI(
    setupManager: SetupManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/profile", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val user = databaseManager.userDao.getById(userId, sqlConnection)!!

        response["registerDate"] = user.registerDate
        response["lastLoginDate"] = user.lastLoginDate

        return Successful(response)
    }
}