package com.panomc.platform.route.api.panel

import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser
import java.io.PrintWriter
import java.io.StringWriter

@Endpoint
class PanelGetPluginsAPI(
    private val pluginManager: PluginManager
) : Api() {
    override val paths = listOf(Path("/api/panel/plugins", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val result = mutableMapOf(
            "plugins" to pluginManager.plugins.map { it as PanoPluginWrapper }.associate {
                it.pluginId to mapOf(
                    "author" to it.descriptor.provider,
                    "version" to it.descriptor.version,
                    "status" to it.pluginState,
                    "dependencies" to it.descriptor.dependencies,
                    "license" to it.descriptor.license,
                    "error" to if (it.failedException == null) null else getStackTraceAsString(it.failedException),
                    "hash" to it.hash
                )
            }
        )

        return Successful(result)
    }

    private fun getStackTraceAsString(exception: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        exception.printStackTrace(printWriter)
        return stringWriter.toString()
    }
}