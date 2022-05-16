package com.panomc.platform.route.api.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.util.PlatformCodeManager
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class ConnectNewAPI(
    private val platformCodeManager: PlatformCodeManager,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/server/connectNew")

    override suspend fun handler(context: RoutingContext): Result? {
        if (!setupManager.isSetupDone()) {
            context.reroute("/")

            return null
        }

        val data = context.bodyAsJson

        val sqlConnection = createConnection(databaseManager, context)

        if (data.getString("platformCode", "") != platformCodeManager.getPlatformKey().toString()) {
            throw Error(ErrorCode.CONNECT_NEW_SERVER_API_PLATFORM_CODE_WRONG)
        }

        val token = databaseManager.serverDao.add(
            Server(
                -1,
                data.getString("serverName"),
                data.getInteger("playerCount"),
                data.getInteger("maxPlayerCount"),
                data.getString("serverType"),
                data.getString("serverVersion"),
                data.getString("favicon"),
                data.getString("status")
            ),
            sqlConnection
        )

        return Successful(
            mapOf(
                "token" to token
            )
        )
    }
}