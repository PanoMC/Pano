package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PanelNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/notifications")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection((this::createConnectionHandler)(handler, userID))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        userID: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.panelNotificationDao.getCountByUserID(
            userID,
            sqlConnection,
            (this::getCountByUserIDHandler)(handler, sqlConnection, userID)
        )
    }

    private fun getCountByUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        userID: Int
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        if (count == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.panelNotificationDao.getLast10ByUserID(
            userID,
            sqlConnection,
            (this::getLast10ByUserIDHandler)(handler, sqlConnection, userID, count)
        )
    }

    private fun getLast10ByUserIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        userID: Int,
        count: Int
    ) = handler@{ notifications: List<PanelNotification>?, _: AsyncResult<*> ->
        if (notifications == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.panelNotificationDao.markReadLast10(
            userID,
            sqlConnection,
            (this::markReadLast10Handler)(handler, sqlConnection, count, userID, notifications)
        )
    }

    private fun markReadLast10Handler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        count: Int,
        userID: Int,
        notifications: List<PanelNotification>
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

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

            handler.invoke(
                Successful(
                    mutableMapOf(
                        "notifications" to notificationsDataList,
                        "notificationCount" to count
                    )
                )
            )
        }
    }
}