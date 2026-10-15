package com.panomc.platform.route.api.widget

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.ui.WidgetRuntime
import com.panomc.platform.schema.EndpointDoc
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * `GET /api/v1/widgets/loader.js` -- the one script a page includes to use Pano's web-component widgets
 * (doc 06 section 3.4). 404 while this Pano has no widget runtime. Cached for 5 minutes. Cross-origin
 * access follows the access plane's origin policy like every API path.
 */
@Endpoint
class GetWidgetLoaderAPI(private val widgetRuntime: WidgetRuntime) : Api() {
    override val paths = listOf(Path("/widgets/loader.js", RouteType.GET))

    // A widget page keeps its widgets during maintenance; the data endpoints they call are gated on their own.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc = EndpointDoc(
        summary = "The one JavaScript file a page includes to use Pano's web-component widgets (not JSON).",
        tag = "widgets",
        binary = true,
        errors = listOf(NotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        val file = context.vertx().executeBlocking<com.panomc.platform.ui.WidgetRuntimeFile?> { widgetRuntime.loader() }.coAwait() ?: throw NotFound()

        WidgetFiles.send(context, file, forceShortCache = true)

        return null
    }
}
