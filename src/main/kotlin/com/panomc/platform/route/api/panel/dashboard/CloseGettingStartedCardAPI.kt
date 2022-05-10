package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class CloseGettingStartedCardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/dashboard/closeGettingStartedCard")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection((this::createConnectionHandler)(handler, userID))
    }

    private fun createConnectionHandler(handler: (result: Result) -> Unit, userID: Int) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
            if (sqlConnection == null) {
                handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                return@handler
            }

            databaseManager.systemPropertyDao.isUserInstalledSystemByUserID(
                userID,
                sqlConnection,
                (this::isUserInstalledSystemByUserIDHandler)(handler, sqlConnection)
            )
        }

    private fun isUserInstalledSystemByUserIDHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection) =
        handler@{ isUserInstalledSystem: Boolean?, _: AsyncResult<*> ->
            if (isUserInstalledSystem == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@handler
            }

            databaseManager.systemPropertyDao.update(
                SystemProperty(
                    -1,
                    "show_getting_started",
                    "false"
                ),
                sqlConnection,
                (this::updateHandler)(handler, sqlConnection)
            )
        }

    private fun updateHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection) =
        handler@{ result: Result?, _: AsyncResult<*> ->
            databaseManager.closeConnection(sqlConnection) {
                if (result == null)
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                else
                    handler.invoke(Successful())
            }
        }
}