package com.panomc.platform.route.api.panel.plugins

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.PanelApi
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
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.InputStream

@Endpoint
class GetPluginBannerAPI(
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/banner", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string

        val pluginWrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        if (pluginWrapper == null) {
            throw NotFound()
        }

        val pluginConfig = pluginWrapper.config ?: throw NotFound()

        val bannerFileName = pluginConfig.getString("banner-file")

        val resource: InputStream = pluginWrapper.getResource(bannerFileName) ?: throw NotFound()

        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(bannerFileName)

        response.putHeader("Content-Type", mimeType)

        response.isChunked = true

        resource.writeToResponse(response)

        response.end()

        return null
    }
}