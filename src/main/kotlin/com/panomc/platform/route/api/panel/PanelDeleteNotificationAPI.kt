package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelDeleteNotificationAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.DELETE

    override val routes = arrayListOf("/api/panel/notifications/:id")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.intSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").integer

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.panelNotificationDao.existsById(id, sqlConnection)

        if (!exists) {
            return Successful()
        }

        val notification =
            databaseManager.panelNotificationDao.getById(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        if (notification.userId != userId) {
            return Successful()
        }

        databaseManager.panelNotificationDao.deleteById(notification.id, sqlConnection)

        return Successful()
    }
}