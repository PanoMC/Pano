package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class HomeSidebarAPI(private val configManager: ConfigManager, private val databaseManager: DatabaseManager) : Api() {
    override val paths = listOf(Path("/api/sidebar/home", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null


    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        response["ipAddress"] = configManager.getConfig().getString("server-ip-address")
        response["serverGameVersion"] = configManager.getConfig().getString("server-game-version")

        val sqlClient = getSqlClient()

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlClient
        )?.value?.toLong()
        var mainServer: Server? = null

        if (mainServerId != null && mainServerId != -1L) {
            mainServer = databaseManager.serverDao.getById(mainServerId, sqlClient)
        }

        response["mainServer"] = if (mainServer == null) null else mapOf<String, Any?>(
            "playerCount" to mainServer.playerCount,
            "maxPlayerCount" to mainServer.maxPlayerCount,
            "status" to mainServer.status
        )
        response["lastRegisteredUsers"] = databaseManager.userDao.getLastUsernames(12, sqlClient)

        return Successful(response)
    }
}