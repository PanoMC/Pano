package com.panomc.platform.route.api.panel

import com.panomc.platform.Main
import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.server.PlatformCodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetBasicDataAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val platformCodeManager: PlatformCodeManager,
    private val configManager: ConfigManager,
    private val updateManager: UpdateManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/basicData", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getById(
            userId,
            sqlClient
        )!!

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlClient)
        val connectedServerCount = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

//        Since it's a panel API, it calls AuthProvider#hasAccessPanel method and these context fields are created
        val isAdmin = context.get<Boolean>("isAdmin") ?: false
        val permissions = context.get<List<String>>("permissions") ?: listOf()
        val panelTheme = databaseManager.panelConfigDao.byUserIdAndOption(userId, "panel_theme", sqlClient)?.value

        val result: MutableMap<String, Any?> = mutableMapOf(
            "user" to mapOf(
                "username" to user.username,
                "email" to user.email,
                "permissions" to permissions,
                "admin" to isAdmin
            ),
            "website" to mapOf(
                "name" to configManager.config.websiteName,
                "description" to configManager.config.websiteDescription,
                "websiteUrl" to configManager.config.websiteUrl,
                "registerAgreement" to configManager.config.registerAgreement
            ),
            "notificationCount" to count,
            "connectedServerCount" to connectedServerCount,
            "acceptPluginAuth" to configManager.config.acceptPluginAuth,
            "panelTheme" to panelTheme,
            "showDevModeAlert" to false,
            "showWhatsNew" to false,
            "currentVersion" to Main.VERSION
        )

        val dismissedVersion = databaseManager.panelConfigDao.byUserIdAndOption(userId, "dismissed_whats_new_version", sqlClient)?.value
        result["showWhatsNew"] = dismissedVersion != Main.VERSION

        if (configManager.config.developmentMode) {
            val option = "dismissed_dev_mode_alert"
            val dismissedProperty = databaseManager.panelConfigDao.byUserIdAndOption(userId, option, sqlClient)
            result["showDevModeAlert"] = dismissedProperty == null || dismissedProperty.value != "true"
        } else {
            // If development mode is disabled, clear any previous dismissals so they reappear if enabled again
            val option = "dismissed_dev_mode_alert"
            databaseManager.panelConfigDao.deleteByOption(option, sqlClient)
        }

        if (authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            val platformUpdate = updateManager.getPlatformUpdateInfo()
            val resourceUpdatesInfo = updateManager.getResourcesUpdateList()

            result["hasUpdate"] = platformUpdate != null || resourceUpdatesInfo.isNotEmpty()
        }

        if (authProvider.hasPermission(ManageServersPermission(), context)) {
            val mainServerId = databaseManager.systemPropertyDao.getByOption(
                "main_server",
                sqlClient
            )?.value?.toLong()
            var mainServer: Server? = null

            if (mainServerId != null && mainServerId != -1L) {
                mainServer = databaseManager.serverDao.getById(mainServerId, sqlClient)
            }

            val selectedServerPanelConfig =
                databaseManager.panelConfigDao.byUserIdAndOption(userId, "selected_server", sqlClient)
            var selectedServer: Server? = null

            if (selectedServerPanelConfig != null) {
                val selectedServerId = selectedServerPanelConfig.value.toLong()

                selectedServer = databaseManager.serverDao.getById(selectedServerId, sqlClient)
            }

            val host = context.request().authority().host()
            val port = context.request().authority().port()
            val platformHostAddress = host + if (port == 80) "" else ":$port"

            result["platformServerMatchKey"] = platformCodeManager.getPlatformKey()
            result["platformServerMatchKeyTimeStarted"] = platformCodeManager.getTimeStarted()
            result["platformHostAddress"] = platformHostAddress

            result["mainServer"] = mainServer
            result["selectedServer"] = selectedServer
        }

        return Successful(result)
    }
}