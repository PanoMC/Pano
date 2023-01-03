package com.panomc.platform.route.api.panel.platformAuth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.server.PlatformCodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetRefreshKeyAPI(
    private val platformCodeManager: PlatformCodeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/platformAuth/refreshKey", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext) = Successful(
        mapOf(
            "key" to platformCodeManager.getPlatformKey(),
            "timeStarted" to platformCodeManager.getTimeStarted()
        )
    )
}