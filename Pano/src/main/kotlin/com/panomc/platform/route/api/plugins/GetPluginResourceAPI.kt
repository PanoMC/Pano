package com.panomc.platform.route.api.plugins

import com.panomc.platform.AppConstants
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
import java.io.InputStream

@Endpoint
class GetPluginResourceAPI(
    private val pluginUiManager: PluginUiManager
) : Api() {
    override val paths = listOf(Path("/api/plugins/:pluginId/resources/*", RouteType.GET))

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
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(fileName.replace("plugin-ui/", ""))

        response.putHeader("Content-Type", mimeType)

        response.isChunked = true

        resource.writeToResponse(response)

        response.end()

        return null
    }
}