package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.ImageValidationUtil
import com.panomc.platform.util.UsageMode
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.security.MessageDigest
import java.util.Base64

/**
 * `GET /api/panel/servers/:id/icon` — one server's icon as an image, for places that only know the
 * server's id, like a notification about it.
 *
 * The icon is stored as a data URL on the server row, which is what every server listing already
 * carries; a notification carries only the id, and copying the image into each one would store
 * the same few kilobytes again with every alert. A server without an icon (or with one that is not
 * a plain raster image) answers 404, and the panel shows its default server icon instead.
 */
@Endpoint
class PanelGetServerIconAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/icon", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServersPermission(), context, id)

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val icon = decode(server.favicon) ?: throw NotExists()

        val etag = "\"" + sha256(icon.second).take(32) + "\""

        val response = context.response()

        // Short and private: an admin who uploads a new icon sees it within minutes, and the
        // browser does not ask again for every notification about the same server.
        response.putHeader("Cache-Control", "private, max-age=$CACHE_TTL_SECONDS")
        response.putHeader("ETag", etag)

        if (context.request().getHeader("If-None-Match") == etag) {
            response.setStatusCode(304).end()

            return null
        }

        response.putHeader("Content-Type", icon.first)
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.end(Buffer.buffer(icon.second))

        return null
    }

    companion object {
        const val CACHE_TTL_SECONDS = 300

        /**
         * The MIME type and bytes of a base64 raster-image data URL, or null for anything else --
         * no icon, an SVG, a data URL that is not base64 or does not decode.
         */
        fun decode(dataUrl: String?): Pair<String, ByteArray>? {
            val value = ImageValidationUtil.sanitizeFaviconDataUrl(dataUrl?.trim()) ?: return null

            val comma = value.indexOf(',')

            if (comma < 0) {
                return null
            }

            val header = value.substring(5, comma)
            val mimeType = header.substringBefore(';').lowercase()

            if (!header.lowercase().split(';').contains("base64")) {
                return null
            }

            val bytes = try {
                Base64.getMimeDecoder().decode(value.substring(comma + 1))
            } catch (_: IllegalArgumentException) {
                return null
            }

            return if (bytes.isEmpty()) null else mimeType to bytes
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
