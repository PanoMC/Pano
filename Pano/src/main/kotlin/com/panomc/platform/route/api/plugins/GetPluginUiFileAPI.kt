package com.panomc.platform.route.api.plugins

import com.panomc.platform.PackageEntry
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginPackage
import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import com.panomc.platform.schema.EndpointDoc

/**
 * `GET /api/v1/plugins/:pluginId/_/ui/{file}` -- one file of a plugin's built package (doc 02 §6) for
 * headless and non-Svelte front-ends: `pano-plugin.json`, files under `contract/`, `controllers/`,
 * `samples/` and `widgets/`. Files under `client/` and `server/`, and every unknown plugin or entry answer 404
 * in the error envelope. Only plugins [PluginUiManager.getActiveRegisteredPlugins] lists are served.
 *
 * The source is the classpath `plugin-ui.zip` (cached per uiHash, the ETag), or the plugin's
 * `plugin-ui` source folder while Development Mode is on.
 */
@Endpoint
class GetPluginUiFileAPI(
    private val pluginUiManager: PluginUiManager,
    private val pluginManager: PluginManager
) : Api() {
    override val paths = listOf(Path("/plugins/:pluginId/_/ui/*", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "One file of a plugin's built package (pano-plugin.json, contract, controllers, samples, widgets).",
        tag = "plugins",
        errors = listOf(NotFound::class),
        binary = true
    )

    // Like `_/ui.zip`: a front-end loads package files while it renders its maintenance page too.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        val pluginId = context.pathParam("pluginId")
        val entry = PluginPackage.normalize(context.pathParam("*"))

        if (pluginId == null || entry == null || !PluginPackage.isServable(entry)) {
            throw NotFound()
        }

        val plugin = pluginUiManager.getActiveRegisteredPlugins(pluginManager)
            .firstOrNull { it.first.pluginId == pluginId }?.first ?: throw NotFound()

        val file = context.vertx().executeBlocking<PackageEntry?> {
            pluginUiManager.readPackageEntry(plugin, entry)
        }.coAwait() ?: throw NotFound()

        send(context, entry, file)

        return null
    }

    private fun send(context: RoutingContext, entry: String, file: PackageEntry) {
        val response = context.response()
        val etag = "\"${file.etag}\""

        response.putHeader("Content-Type", PluginPackage.contentType(entry))
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "no-cache")

        val ifNoneMatch = context.request().getHeader("If-None-Match")

        if (ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }?.contains(etag) == true) {
            response.setStatusCode(304).end()

            return
        }

        response.putHeader("Content-Length", file.bytes.size.toString())
        response.end(Buffer.buffer(file.bytes))
    }
}
