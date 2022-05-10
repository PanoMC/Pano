package com.panomc.platform.route.api

import com.panomc.platform.ErrorCode
import com.panomc.platform.TestSendNotificationService
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.NotificationStatus
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class TestSendNotificationAPI(
    private val service: TestSendNotificationService,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/testNotification")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val userID = authProvider.getUserIDFromRoutingContext(context)

//        testServiceSpring.sayHello()

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

        databaseManager.panelNotificationDao.add(
            getNotification(userID),
            sqlConnection,
            (this::addHandler)(handler, sqlConnection)
        )
    }

    private fun getNotification(userID: Int) = PanelNotification(
        -1,
        userID,
        "TEST NOTIFICATION",
        System.currentTimeMillis(),
        NotificationStatus.NOT_READ
    )

    private fun addHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}