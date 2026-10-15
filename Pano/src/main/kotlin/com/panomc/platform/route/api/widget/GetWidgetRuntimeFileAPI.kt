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
 * `GET /api/v1/widgets/runtime/{file}` -- one file of the widget runtime (Svelte, host modules, styles, fonts;
 * doc 06 section 3.3). Content-hashed names (`chunks/chunk-<hash>.js`) are `immutable`, the rest is
 * validated by the runtime hash and cached for 5 minutes. 404 for anything the runtime does not list and
 * for a Pano without a runtime.
 */
@Endpoint
class GetWidgetRuntimeFileAPI(private val widgetRuntime: WidgetRuntime) : Api() {
    override val paths = listOf(Path("/widgets/runtime/*", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc = EndpointDoc(
        summary = "One file of the widget runtime (Svelte, host modules, styles, fonts); the answer is the file, not JSON.",
        tag = "widgets",
        binary = true,
        errors = listOf(NotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        val entry = context.pathParam("*")

        val file = context.vertx().executeBlocking<com.panomc.platform.ui.WidgetRuntimeFile?> { widgetRuntime.file(entry ?: "") }.coAwait() ?: throw NotFound()

        WidgetFiles.send(context, file)

        return null
    }
}
