package com.panomc.platform.route.api

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.MimeTypeUtil
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.imgscalr.Scalr
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

@Endpoint
class GetPostThumbnailAPI(private val configManager: ConfigManager) : Api() {
    override val paths = listOf(Path("/api/post/thumbnail/:filename", RouteType.GET))

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("filename", stringSchema()))
            .queryParameter(optionalParam("preview", booleanSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val filename = parameters.pathParameter("filename").string
        val preview = parameters.queryParameter("preview")?.boolean ?: false

        val path = configManager.config
            .fileUploadsFolder + File.separator + AppConstants.DEFAULT_POST_THUMBNAIL_UPLOAD_PATH + File.separator +
                filename

        val file = File(path)

        if (!file.exists()) {
            context.response().setStatusCode(404).end()

            return null
        }

        val actualHash = File(path).inputStream().hash()

        val etag = "\"$actualHash\"" // strong ETag
        val ifNoneMatch = context.request().getHeader("If-None-Match")
        if (ifNoneMatch?.split(',')?.map { it.trim() }?.contains(etag) == true) {
            context.response()
                .setStatusCode(304)
                .putHeader("ETag", etag)
                .putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")
                .end()
            return null
        }

        val mimeType = MimeTypeUtil.getMimeTypeFromFileName(path)

        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        if (preview) {
            var image: BufferedImage? = null

            try {
                image = ImageIO.read(file)
            } catch (_: Exception) {
            }

            if (image != null) {
                val src = ImageIO.read(file)

                val argb = if (src.type != BufferedImage.TYPE_INT_ARGB) {
                    val tmp = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
                    val g = tmp.createGraphics()
                    g.composite = java.awt.AlphaComposite.Src
                    g.drawImage(src, 0, 0, null)
                    g.dispose()
                    tmp
                } else src

                val scaled = Scalr.resize(argb, Scalr.Method.QUALITY, Scalr.Mode.AUTOMATIC, 100)

                val baos = ByteArrayOutputStream()
                ImageIO.write(scaled, "png", baos)

                context.response()
                    .putHeader("Content-Type", "image/png")
                    .end(Buffer.buffer(baos.toByteArray()))
                return null
            }
        }

        response.sendFile(path)

        return null
    }
}