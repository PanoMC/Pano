package com.panomc.platform.route.api.notification

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/notifications", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val count = databaseManager.notificationDao.getCountByUserId(userId, sqlClient)

        val notifications = databaseManager.notificationDao.getLast10ByUserId(userId, sqlClient)

        if (!Main.IS_DEMO) {
            databaseManager.notificationDao.markReadLast10(userId, sqlClient)
        }

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "type" to notification.type.getName(),
                    "details" to notification.details.map,
                    "status" to notification.status,
                    "isPersonal" to (notification.userId == userId),
                    "createdAt" to notification.createdAt,
                    "updatedAt" to notification.updatedAt,
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