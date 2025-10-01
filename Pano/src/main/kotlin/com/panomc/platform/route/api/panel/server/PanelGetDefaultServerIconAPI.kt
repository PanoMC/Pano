package com.panomc.platform.route.api.panel.server

import com.panomc.platform.AppConstants.DEFAULT_WEBSITE_ICON_FILE
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetDefaultServerIconAPI : PanelApi() {
    override val paths = listOf(Path("/api/server/icon/default", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        context.response().sendFile(DEFAULT_WEBSITE_ICON_FILE)

        return null
    }
}