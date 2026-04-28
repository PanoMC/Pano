package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/notifications", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val count = databaseManager.panelNotificationDao.getCountByUserId(userId, sqlClient)

        val notifications = databaseManager.panelNotificationDao.getLast10ByUserId(userId, sqlClient)

        if (!Main.IS_DEMO) {
            databaseManager.panelNotificationDao.markReadLast10(userId, sqlClient)
        }

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "type" to notification.type.getName(),
                    "details" to notification.details.map,
                    "status" to notification.status.name,
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