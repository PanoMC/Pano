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
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.InputStream

@Endpoint
class PanelGetPluginLogoAPI(
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/logo", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .queryParameter(optionalParam("hash", stringSchema()))
            .build()

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val pluginId = parameters.pathParameter("pluginId").string

        val pluginWrapper =
            pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId } ?: throw NotFound()
        val pluginConfig = pluginWrapper.config
        val logoFileName = pluginConfig?.getString("logo-file")

        val requestedHash = parameters.queryParameter("hash")?.string

        // If logo is not configured or file not found in plugin resources, send default
        if (logoFileName == null || pluginWrapper.getResource(logoFileName) == null) {
            sendDefault(context, requestedHash)
            return null
        }

        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(logoFileName)
        val actualHash = pluginWrapper.getResource(logoFileName)?.hash() ?: run {
            // Should be covered by if check above, but for safety
            sendDefault(context, requestedHash)
            return null
        }

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

        // Send file
        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        response.putHeader("Content-Disposition", "inline; filename=\"$logoFileName\"")

        response.isChunked = true
        val resource: InputStream = pluginWrapper.getResource(logoFileName)!!
        resource.writeToResponse(context.vertx(), response)
        response.end()

        return null
    }

    private suspend fun sendDefault(context: RoutingContext, requestedHash: String?) {
        val path = "assets/img/default-plugin.png"
        val inputStream = this.javaClass.classLoader.getResourceAsStream(path)

        if (inputStream == null) {
            context.response().setStatusCode(404).end()
            return
        }

        val actualHash = inputStream.use { it.hash() }

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL
            val redirectUrl = "${context.request().path()}?hash=$actualHash"
            context.response()
                .setStatusCode(302)
                .putHeader("Location", redirectUrl)
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
            context.response()
                .setStatusCode(302)
                .putHeader("Location", redirectUrl)
                .putHeader("Cache-Control", "no-store")
                .end()
            return
        }

        val response = context.response()
        response.putHeader("Content-Type", "image/png")
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
        response.putHeader("Content-Disposition", "inline; filename=\"default-plugin.png\"")
        response.isChunked = true

        this.javaClass.classLoader.getResourceAsStream(path)?.writeToResponse(context.vertx(), response)
        response.end()
    }

}