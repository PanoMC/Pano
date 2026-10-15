package com.panomc.platform.route.api.panel.server

import com.panomc.platform.AppConstants.DEFAULT_FAVICON_FILE
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.route.Namespace
import com.panomc.platform.schema.Stability
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetDefaultServerIconAPI : PanelApi() {
    override val paths = listOf(Path("/server/icon/default", RouteType.GET))

    // A PanelApi by permission, but the file is served from the site namespace (/server/icon/default).
    override val namespace = Namespace.SITE

    // Panel-only by permission, whatever namespace the path sits in: no public promise.
    override val stability = Stability.INTERNAL

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        context.response().sendFile(DEFAULT_FAVICON_FILE)

        return null
    }
}