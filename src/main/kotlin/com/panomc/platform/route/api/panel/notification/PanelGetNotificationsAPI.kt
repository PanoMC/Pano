package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/notifications", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.panelNotificationDao.getCountByUserId(userId, sqlConnection)

        val notifications = databaseManager.panelNotificationDao.getLast10ByUserId(userId, sqlConnection)

        databaseManager.panelNotificationDao.markReadLast10(userId, sqlConnection)

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