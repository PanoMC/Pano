package com.panomc.platform.route.api

import com.panomc.platform.AppConstants.DEFAULT_FAVICON_FILE
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class GetFaviconAPI(private val configManager: ConfigManager) : Api() {
    override val paths = listOf(Path("/api/favicon", RouteType.GET))

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("hash", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val requestedHash = parameters.queryParameter("hash")?.string

        val faviconPath = configManager.config.filePaths["favicon"]

        if (faviconPath == null) {
            sendDefault(context, requestedHash)

            return null
        }

        val path = configManager.config.fileUploadsFolder + File.separator +
                faviconPath

        val file = File(path)

        if (!file.exists()) {
            sendDefault(context, requestedHash)

            return null
        }

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL
            val actualHash = File(path).inputStream().hash()

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

        val actualHash = File(path).inputStream().hash()

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
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)

        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        response.sendFile(path)

        return null
    }


    private fun sendDefault(context: RoutingContext, requestedHash: String?) {
        val path = DEFAULT_FAVICON_FILE

        val file = systemClassLoader.getResourceAsStream(path)!!

        if (requestedHash == null) {
            // No hash → calculate and route to canonical URL
            val actualHash = file.hash()

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

        val actualHash = file.hash()

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
        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)

        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
        response.sendFile(path)
    }
}