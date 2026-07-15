package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.AppConstants.UPDATE_ICON_FOLDER
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class PanelGetUpdateIconAPI(
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/icon/:filename", RouteType.GET))

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("filename", stringSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .queryParameter(optionalParam("hash", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val filename = parameters.pathParameter("filename").string
        val type = parameters.queryParameter("type").string
        val requestedHash = parameters.queryParameter("hash")?.string
        val updateIconFolder = configManager.config.fileUploadsFolder + File.separator + UPDATE_ICON_FOLDER

        val path = updateIconFolder + filename

        val file = File(path)

        if (!file.exists()) {
            sendDefault(context, requestedHash, type)
            return null
        }

        val actualHash = file.inputStream().use { it.hash() }

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL
            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            if (type != null) {
                // Keep type parameter if present
                context.response()
                    .setStatusCode(302)
                    .putHeader("Location", "$redirectUrl&type=$type")
                    .putHeader("Cache-Control", "no-store") // Do not cache this one
                    .end()
            } else {
                context.response()
                    .setStatusCode(302)
                    .putHeader("Location", redirectUrl)
                    .putHeader("Cache-Control", "no-store") // Do not cache this one
                    .end()
            }
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
            if (type != null) {
                context.response()
                    .setStatusCode(302)
                    .putHeader("Location", "$redirectUrl&type=$type")
                    .putHeader("Cache-Control", "no-store")
                    .end()
            } else {
                context.response()
                    .setStatusCode(302)
                    .putHeader("Location", redirectUrl)
                    .putHeader("Cache-Control", "no-store")
                    .end()
            }
            return null
        }

        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)

        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        try {
            response.sendFile(path)
        } catch (_: Exception) {
            response.setStatusCode(404).end()
        }

        return null
    }

    private suspend fun sendDefault(context: RoutingContext, requestedHash: String?, type: String?) {
        val defaultIcon = if (type == "THEME") "assets/img/default-theme.png" else "assets/img/default-plugin.png"
        val inputStream = this.javaClass.classLoader.getResourceAsStream(defaultIcon)

        if (inputStream == null) {
            context.response().setStatusCode(404).end()
            return
        }

        val actualHash = inputStream.use { it.hash() }

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL
            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            val finalRedirectUrl = if (type != null) "$redirectUrl&type=$type" else redirectUrl

            context.response()
                .setStatusCode(302)
                .putHeader("Location", finalRedirectUrl)
                .putHeader("Cache-Control", "no-store") // Do not cache this one
                .end()
            return
        }

        val etag = "\"$requestedHash\"" // strong ETag
        val ifNoneMatch = context.request().getHeader("If-None-Match")
        if (ifNoneMatch?.split(',')?.map { it.trim() }?.contains(etag) == true) {
            context.response()
                .setStatusCode(304)
                .putHeader("ETag", etag)
                .putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
                .end()
            return
        }

        if (!requestedHash.equals(actualHash, ignoreCase = true)) {
            // Wrong hash → route to correct one
            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            val finalRedirectUrl = if (type != null) "$redirectUrl&type=$type" else redirectUrl

            context.response()
                .setStatusCode(302)
                .putHeader("Location", finalRedirectUrl)
                .putHeader("Cache-Control", "no-store")
                .end()
            return
        }

        val response = context.response()
        response.putHeader("Content-Type", "image/png")
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
        response.isChunked = true

        com.panomc.platform.util.FileResourceUtil.run {
            this@PanelGetUpdateIconAPI.javaClass.classLoader.getResourceAsStream(defaultIcon)
                ?.writeToResponse(context.vertx(), response)
        }
        
        response.end()
    }
}