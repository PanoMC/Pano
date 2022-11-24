package com.panomc.platform.route.api.panel.dashboard

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelCloseGettingStartedCardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/dashboard/closeGettingStartedCard", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val isUserInstalledSystem =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserId(userId, sqlConnection)

        if (!isUserInstalledSystem) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        databaseManager.systemPropertyDao.update(
            SystemProperty(
                option = "show_getting_started",
                value = "false"
            ),
            sqlConnection
        )

        return Successful()
    }
}