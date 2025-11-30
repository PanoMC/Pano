package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
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
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetThemeScreenshotAPI(
    private val uiManager: UIManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId/screenshots/*", RouteType.GET))

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .pathParameter(param("*", stringSchema()))
            .queryParameter(optionalParam("hash", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string
        val fileName = parameters.pathParameter("*").string

        val requestedHash = parameters.queryParameter("hash")?.string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()
        val themeScreenshot = theme.screenshots[fileName]

        if (themeScreenshot == null) {
            sendPlaceHolder(context, themeId)

            return null
        }

        val actualHash = themeScreenshot

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL

            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            context.response()
                .setStatusCode(302)
                .putHeader("Location", redirectUrl)
                .putHeader("Cache-Control", "no-store") // Do not cache this one
                .end()
            return null
        }

        val etag = "\"$requestedHash\"" // strong ETag
        val ifNoneMatch = context.request().getHeader("If-None-Match")
        if (ifNoneMatch?.split(',')?.map { it.trim() }?.contains(etag) == true) {
            context.response()
                .setStatusCode(304)
                .putHeader("ETag", etag)
                .putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
                .end()
            return null
        }

        if (!requestedHash.equals(actualHash, ignoreCase = true)) {
            // Wrong hash → route to correct one
            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            context.response()
                .setStatusCode(302)
                .putHeader("Location", redirectUrl)
                .putHeader("Cache-Control", "no-store")
                .end()
            return null
        }

        val screenshotFile = uiManager.getThemeFileAsStream(themeId, fileName) ?: throw NotFound()

        val response = context.response()
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(fileName)

        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        response.putHeader("Content-Disposition", "inline; filename=\"$fileName\"")

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