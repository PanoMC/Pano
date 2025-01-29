package com.panomc.platform.route.api.plugins

import com.panomc.platform.PluginUiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.FileResourceUtil.getResource
import com.panomc.platform.util.FileResourceUtil.writeToResponse
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.withContext

@Endpoint
class GetPluginUiZipAPI(
    private val pluginUiManager: PluginUiManager
) : Api() {
    override val paths = listOf(Path("/api/plugins/:pluginId/resources/plugin-ui.zip", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string

        val plugin =
            pluginUiManager.getRegisteredPlugins().toList().firstOrNull { it.first.pluginId == pluginId }?.first

        if (plugin == null) {
            throw NotFound()
        }

        val pluginUiZipFileName = "plugin-ui.zip"

        val resource = plugin.getResource(pluginUiZipFileName) ?: throw NotFound()

        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(pluginUiZipFileName)

        response.putHeader("Content-Type", mimeType)

        response.isChunked = true

        resource.writeToResponse(response)

        withContext(context.vertx().dispatcher()) {
            resource.close()
        }

        response.end()

        return null
    }
}