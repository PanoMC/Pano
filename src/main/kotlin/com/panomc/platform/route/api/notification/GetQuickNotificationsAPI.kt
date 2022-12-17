package com.panomc.platform.route.api.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetQuickNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : LoggedInApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/notifications/quick", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val notifications = databaseManager.notificationDao.getLast5ByUserId(userId, sqlConnection)

        val count = databaseManager.notificationDao.getCountOfNotReadByUserId(userId, sqlConnection)

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "typeId" to notification.typeId,
                    "action" to notification.action,
                    "properties" to notification.properties,
                    "date" to notification.date,
                    "status" to notification.status,
                    "isPersonal" to (notification.userId == userId)
                )
            )
        }

        return Successful(
            mutableMapOf<String, Any?>(
                "notifications" to notificationsDataList,
                "notificationCount" to count
            )
        )
    }
}