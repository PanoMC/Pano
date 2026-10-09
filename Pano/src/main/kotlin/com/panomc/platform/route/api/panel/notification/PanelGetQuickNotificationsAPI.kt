package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationTypeRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetQuickNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationTypeRegistry: NotificationTypeRegistry
) : PanelApi() {
    override val paths = listOf(Path("/notifications/quick", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val notifications = databaseManager.panelNotificationDao.getLast5ByUserId(userId, sqlClient)

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlClient)

        val total = databaseManager.panelNotificationDao.getCountByUserId(userId, sqlClient)

        return Successful(
            PanelNotificationPage.payload(
                notifications,
                userId,
                notificationTypeRegistry::ownerOf,
                PanelNotificationPage.QUICK_SIZE,
                total > notifications.size,
                mapOf("notificationCount" to count)
            )
        )
    }
}
