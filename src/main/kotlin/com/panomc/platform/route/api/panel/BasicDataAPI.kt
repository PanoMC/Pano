package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.PlatformCodeManager
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class BasicDataAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val platformCodeManager: PlatformCodeManager,
    private val configManager: ConfigManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/basicData")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection((this::createConnectionHandler)(handler, context, userID))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        userID: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.userDao.getByID(
            userID,
            sqlConnection,
            (this::getByIDHandler)(handler, context, sqlConnection, userID)
        )
    }

    private fun getByIDHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        sqlConnection: SqlConnection,
        userID: Int
    ) = handler@{ user: User?, _: AsyncResult<*> ->
        if (user == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.panelNotificationDao.getCountOfNotReadByUserID(
            userID,
            sqlConnection,
            (this::getCountByUserIDHandler)(handler, context, sqlConnection, user)
        )
    }

    private fun getCountByUserIDHandler(
        handler: (result: Result) -> Unit,
        context: RoutingContext,
        sqlConnection: SqlConnection,
        user: User
    ) = handler@{ count: Int?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (count == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "user" to mapOf(
                            "username" to user.username,
                            "email" to user.email,
                            "permissionId" to user.permissionGroupID
                        ),
                        "website" to mapOf(
                            "name" to configManager.getConfig().getString("website-name"),
                            "description" to configManager.getConfig().getString("website-description")
                        ),
                        "platformServerMatchKey" to platformCodeManager.getPlatformKey(),
                        "platformServerMatchKeyTimeStarted" to platformCodeManager.getTimeStarted(),
                        "platformHostAddress" to context.request().host(),
                        "servers" to listOf<Map<String, Any?>>(),
                        "notificationCount" to count
                    )
                )
            )
        }
    }
}