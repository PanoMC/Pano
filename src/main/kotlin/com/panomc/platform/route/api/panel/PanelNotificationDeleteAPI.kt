package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PanelNotificationDeleteAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/notifications/delete")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getInteger("id")
        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.panelNotificationDao.existsByID(id, sqlConnection)

        if (!exists) {
            return Successful()
        }

        val notification =
            databaseManager.panelNotificationDao.getByID(id, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        if (notification.userID != userID) {
            return Successful()
        }

        databaseManager.panelNotificationDao.deleteByID(notification.id, sqlConnection)

        return Successful()
    }
}