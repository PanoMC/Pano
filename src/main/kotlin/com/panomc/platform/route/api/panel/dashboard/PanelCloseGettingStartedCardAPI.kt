package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelCloseGettingStartedCardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/dashboard/closeGettingStartedCard", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val isUserInstalledSystem =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserId(userId, sqlClient)

        if (!isUserInstalledSystem) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        databaseManager.systemPropertyDao.update(
            "show_getting_started",
            "false",
            sqlClient
        )

        return Successful()
    }
}