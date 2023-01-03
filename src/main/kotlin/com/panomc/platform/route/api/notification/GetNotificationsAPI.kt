package com.panomc.platform.route.api.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/notifications", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val count = databaseManager.notificationDao.getCountByUserId(userId, sqlConnection)

        val notifications = databaseManager.notificationDao.getLast10ByUserId(userId, sqlConnection)

        databaseManager.notificationDao.markReadLast10(userId, sqlConnection)

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "type" to notification.type,
                    "properties" to notification.properties,
                    "date" to notification.date,
                    "status" to notification.status,
                    "isPersonal" to (notification.userId == userId)
                )
            )
        }

        return Successful(
            mutableMapOf(
                "notifications" to notificationsDataList,
                "notificationCount" to count
            )
        )
    }
}