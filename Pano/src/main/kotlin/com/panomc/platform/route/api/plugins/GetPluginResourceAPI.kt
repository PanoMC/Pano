package com.panomc.platform.route.api.plugins

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.FileResourceUtil.getResource
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.apache.tika.Tika
import java.io.InputStream

@Endpoint
class GetPluginResourceAPI(
    private val configManager: ConfigManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager
) : Api() {
    override val paths = listOf(Path("/api/plugins/:pluginId/resources/*", RouteType.GET))

    private val tika by lazy {
        Tika()
    }

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("pluginId", stringSchema()))
            .pathParameter(param("*", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val fileName = parameters.pathParameter("*").string
        val pluginId = parameters.pathParameter("pluginId").string

        if (!fileName.startsWith(AppConstants.pluginUiFolder)) {
            throw NotFound()
        }

        val plugin =
            pluginUiManager.getRegisteredPlugins().toList().firstOrNull { it.first.pluginId == pluginId }?.first

        if (plugin == null) {
            throw NotFound()
        }

        val resource: InputStream = plugin.getResource(fileName) ?: throw NotFound()

        val response = context.response()
        val mimeType = getMimeTypeFromFileName(fileName.replace("plugin-ui/", ""))

        response.putHeader("Content-Type", mimeType)

        response.isChunked = true

        resource.use {
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (it.read(buffer).also { bytesRead = it } != -1) {
                response.write(Buffer.buffer(buffer.copyOfRange(0, bytesRead)))
            }
        }

        response.end()

        return null
    }

    private fun getMimeTypeFromFileName(fileName: String): String {
        val split = fileName.split(".")

        val extension = split[split.size - 1]

        if (extension == "mjs" || extension == "js") {
            return "text/javascript"
        }

        return tika.detect(fileName)
    }
}