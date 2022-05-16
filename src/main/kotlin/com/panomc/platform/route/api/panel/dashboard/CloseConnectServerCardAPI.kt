package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class CloseConnectServerCardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/dashboard/closeConnectServerCard")

    override suspend fun handler(context: RoutingContext): Result {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val isUserInstalledSystem =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserID(userID, sqlConnection)

        if (!isUserInstalledSystem) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        databaseManager.systemPropertyDao.update(
            SystemProperty(
                -1,
                "false",
                "show_connect_server_info"
            ),
            sqlConnection
        )

        return Successful()
    }
}