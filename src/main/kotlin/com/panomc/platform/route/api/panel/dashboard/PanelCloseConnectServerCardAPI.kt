package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelCloseConnectServerCardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/dashboard/closeConnectServerCard", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val isUserInstalledSystem =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserId(userId, sqlConnection)

        if (!isUserInstalledSystem) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        databaseManager.systemPropertyDao.update(
            "show_connect_server_info",
            "false",
            sqlConnection
        )

        return Successful()
    }
}