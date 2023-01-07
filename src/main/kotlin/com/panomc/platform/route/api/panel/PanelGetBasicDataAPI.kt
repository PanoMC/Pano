package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.server.PlatformCodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetBasicDataAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val platformCodeManager: PlatformCodeManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/basicData", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val user = databaseManager.userDao.getById(
            userId,
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlConnection)

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlConnection
        )?.value?.toLong()
        var mainServer: Server? = null

        if (mainServerId != null && mainServerId != -1L) {
            mainServer = databaseManager.serverDao.getById(mainServerId, sqlConnection)
        }

        val selectedServerPanelConfig =
            databaseManager.panelConfigDao.byUserIdAndOption(userId, "selected_server", sqlConnection)
        var selectedServer: Server? = null

        if (selectedServerPanelConfig != null) {
            val selectedServerId = selectedServerPanelConfig.value.toLong()

            selectedServer = databaseManager.serverDao.getById(selectedServerId, sqlConnection)
        }

//        Since it's a panel API, it calls AuthProvider#hasAccessPanel method and these context fields are created
        val isAdmin = context.get<Boolean>("isAdmin") ?: false
        val permissions = context.get<List<Permission>>("permissions") ?: listOf()

        return Successful(
            mapOf(
                "user" to mapOf(
                    "username" to user.username,
                    "email" to user.email,
                    "permissions" to permissions.map { it.name },
                    "admin" to isAdmin
                ),
                "website" to mapOf(
                    "name" to configManager.getConfig().getString("website-name"),
                    "description" to configManager.getConfig().getString("website-description")
                ),
                "platformServerMatchKey" to platformCodeManager.getPlatformKey(),
                "platformServerMatchKeyTimeStarted" to platformCodeManager.getTimeStarted(),
                "platformHostAddress" to context.request().host(),
                "mainServer" to mainServer,
                "selectedServer" to selectedServer,
                "notificationCount" to count,
                "locale" to configManager.getConfig().getString("locale")
            )
        )
    }
}