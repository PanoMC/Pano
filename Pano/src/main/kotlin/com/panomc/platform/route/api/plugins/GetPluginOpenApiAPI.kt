package com.panomc.platform.route.api.plugins

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.ErrorCatalogProvider
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.RouteTable
import com.panomc.platform.route.api.openapi.OpenApiResponseDoc
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.OpenApiGenerator
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState

/**
 * `GET /api/v1/plugins/:pluginId/_/openapi.json` -- the OpenAPI 3.1 document of one plugin's public API (doc 04
 * section 5). Core serves it from the route table, so a closed-source plugin needs nothing of its own. 404 for a plugin
 * that is unknown or not started. A plugin without public endpoints answers a valid document with no paths.
 *
 * It lives in this package because core paths that start with `plugins/` are reserved to it (doc 04 section 2).
 */
@Endpoint
class GetPluginOpenApiAPI(
    private val routeTable: RouteTable,
    private val pluginManager: PluginManager
) : Api() {
    override val paths = listOf(Path("/plugins/:pluginId/_/openapi.json", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc: EndpointDoc = OpenApiResponseDoc.of(
        "The OpenAPI 3.1 document of one plugin's public API",
        errors = listOf(NotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val pluginId = getParameters(context).pathParameter("pluginId").string

        val wrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        requireStarted(wrapper?.pluginState)

        return Successful(
            OpenApiGenerator.generate(
                OpenApiGenerator.Scope.Plugin(pluginId),
                routeTable.entriesOf(pluginId),
                errorCatalog = catalogOf(wrapper?.plugin)
            ).map
        )
    }

    companion object {
        /** The entries of every `ErrorCatalogProvider` bean of [plugin]; nothing for a plugin without a bean context. */
        fun catalogOf(plugin: Any?) = runCatching {
            (plugin as? PanoPlugin)?.pluginBeanContext?.getBeansOfType(ErrorCatalogProvider::class.java)?.values
                ?.flatMap { it.entries }
        }.getOrNull().orEmpty()

        /** 404 for a plugin that is unknown (null state) or not started. */
        fun requireStarted(pluginState: PluginState?) {
            if (pluginState != PluginState.STARTED) {
                throw NotFound()
            }
        }
    }
}
