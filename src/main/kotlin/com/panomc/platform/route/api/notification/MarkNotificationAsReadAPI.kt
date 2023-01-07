package com.panomc.platform.route.api.notification

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class MarkNotificationAsReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
) : LoggedInApi() {
    override val paths = listOf(Path("/api/notifications/:id/read", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val exists = databaseManager.notificationDao.existsById(id, sqlConnection)

        if (!exists) {
            return Successful()
        }

        val notification =
            databaseManager.notificationDao.getById(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        if (notification.userId != userId) {
            return Successful()
        }

        databaseManager.notificationDao.markReadById(id, sqlConnection)

        return Successful()
    }
}