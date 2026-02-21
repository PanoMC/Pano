package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class HomeSidebarAPI(private val configManager: ConfigManager, private val databaseManager: DatabaseManager) : Api() {
    override val paths = listOf(Path("/api/sidebars/home", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null


    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        response["ipAddress"] = configManager.config.serverIpAddress
        response["serverGameVersion"] = configManager.config.serverGameVersion

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
        response["lastRegisteredUsers"] = databaseManager.userDao.getLastUsers(12, sqlClient).map {
            JsonObject()
                .put("username", it.username)
                .put("registerDate", it.registerDate)
                .put("lastActivityTime", it.lastActivityTime)
        }

        return Successful(response)
    }
}