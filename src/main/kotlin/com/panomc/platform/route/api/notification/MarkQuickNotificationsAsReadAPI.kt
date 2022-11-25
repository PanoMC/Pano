package com.panomc.platform.route.api.notification

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
class MarkQuickNotificationsAsReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : LoggedInApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/notifications/quick/markAsRead", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.notificationDao.markReadLast5ByUserId(userId, sqlConnection)

        val count = databaseManager.notificationDao.getCountOfNotReadByUserId(userId, sqlConnection)

        return Successful(
            mutableMapOf(
                "notificationCount" to count
            )
        )
    }
}