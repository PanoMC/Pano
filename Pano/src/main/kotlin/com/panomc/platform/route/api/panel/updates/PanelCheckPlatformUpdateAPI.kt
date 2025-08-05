package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelCheckPlatformUpdateAPI(
    private val databaseManager: DatabaseManager,
    private val updateManager: UpdateManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/platform", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        updateManager.checkPlatformUpdate()

        val sqlClient = databaseManager.getSqlClient()

        val property =
            databaseManager.systemPropertyDao.getByOption(UpdateManager.PLATFORM_UPDATE_CHECK_INFO, sqlClient)

        return Successful(
            mapOf(
                "data" to property
            )
        )
    }
}