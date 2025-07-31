package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.FileResourceUtil.writeToResponse
import com.panomc.platform.util.MimeTypeUtil
import com.panomc.platform.util.PlaceholderUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class GetThemeScreenshotAPI(
    private val uiManager: UIManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId/screenshots/*", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .pathParameter(param("*", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string
        val fileName = parameters.pathParameter("*").string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()

        if (!theme.screenshots.contains(fileName)) {
            sendPlaceHolder(context, themeId)

            return null
        }

        val screenshotFile = uiManager.getThemeFileAsStream(themeId, fileName) ?: throw NotFound()

        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(fileName)

        response.putHeader("Content-Type", mimeType)

        response.isChunked = true

        screenshotFile.writeToResponse(response)

        response.end()

        return null
    }

    fun sendPlaceHolder(context: RoutingContext, themeId: String) {
        val placeholder = PlaceholderUtil.generateImage(400, 250, themeId)

        PlaceholderUtil.sendPlaceholder(context, placeholder)
    }
}