package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PanelQuickNotificationsAndReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/quickNotificationsAndRead")

    override suspend fun handler(context: RoutingContext): Result {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val notifications = databaseManager.panelNotificationDao.getLast5ByUserID(userID, sqlConnection)

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserID(userID, sqlConnection)

        databaseManager.panelNotificationDao.markReadLat5ByUserID(userID, sqlConnection)

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "typeId" to notification.typeID,
                    "date" to notification.date,
                    "status" to notification.status,
                    "isPersonal" to (notification.userID == userID)
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