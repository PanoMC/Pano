package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelMarkQuickNotificationsAsReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/notifications/quick/markAsRead", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        databaseManager.panelNotificationDao.markReadLast5ByUserId(userId, sqlConnection)

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlConnection)

        return Successful(
            mutableMapOf(
                "notificationCount" to count
            )
        )
    }
}