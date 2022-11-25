package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.PlatformCodeManager
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetBasicDataAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val platformCodeManager: PlatformCodeManager,
    private val configManager: ConfigManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/basicData", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val user = databaseManager.userDao.getById(
            userId,
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlConnection)

        return Successful(
            mapOf(
                "user" to mapOf(
                    "username" to user.username,
                    "email" to user.email,
                    "permissionId" to user.permissionGroupId
                ),
                "website" to mapOf(
                    "name" to configManager.getConfig().getString("website-name"),
                    "description" to configManager.getConfig().getString("website-description")
                ),
                "platformServerMatchKey" to platformCodeManager.getPlatformKey(),
                "platformServerMatchKeyTimeStarted" to platformCodeManager.getTimeStarted(),
                "platformHostAddress" to context.request().host(),
                "servers" to listOf<Map<String, Any?>>(),
                "notificationCount" to count,
                "locale" to configManager.getConfig().getString("locale")
            )
        )
    }
}