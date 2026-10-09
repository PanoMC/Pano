package com.panomc.platform.route.api.plugins

import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `GET /api/v1/plugin-packages` -- `{ "plugins": { "<pluginId>": <pano-plugin.json> } }` for the
 * store and panel badges (doc 02 §6). A core path outside the plugin namespace. Plugins the site
 * serves (see [GetPluginUiFileAPI]) and whose package has a readable `pano-plugin.json`.
 */
@Endpoint
class GetPluginPackagesAPI(
    private val pluginUiManager: PluginUiManager,
    private val pluginManager: PluginManager
) : Api() {
    override val paths = listOf(Path("/plugin-packages", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The pano-plugin.json of every plugin whose site UI Pano serves.",
        tag = "plugins",
        response = objectSchema().requiredProperty("plugins", objectSchema().additionalProperties(objectSchema()))
    )

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val active = pluginUiManager.getActiveRegisteredPlugins(pluginManager)

        val plugins = context.vertx().executeBlocking<JsonObject> {
            val result = JsonObject()

            active.map { it.first }.sortedBy { it.pluginId }.forEach { plugin ->
                pluginUiManager.manifest(plugin)?.let { result.put(plugin.pluginId, it) }
            }

            result
        }.coAwait()

        return Successful(mapOf("plugins" to plugins))
    }
}
