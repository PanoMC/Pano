package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class HomeSidebarAPI(private val configManager: ConfigManager, private val databaseManager: DatabaseManager) : Api() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/sidebar/home")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .build()


    override suspend fun handler(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        response["ipAddress"] = configManager.getConfig().getString("server-ip-address")

        val sqlConnection = createConnection(databaseManager, context)

        response["lastRegisteredUsers"] = databaseManager.userDao.getLastUsernames(12, sqlConnection)

        return Successful(response)
    }
}