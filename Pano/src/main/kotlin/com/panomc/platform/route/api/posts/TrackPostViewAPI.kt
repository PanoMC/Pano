package com.panomc.platform.route.api.posts

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.security.MessageDigest

@Endpoint
class TrackPostViewAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : Api() {
    companion object {
        private const val DEDUPE_WINDOW_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val VIEWER_ID_HEADER = "X-Post-Viewer-Id"
        private const val USER_AGENT_MAX_LENGTH = 255

        private val viewerIdRegex = Regex("^[A-Za-z0-9_-]{16,128}$")
        private val botUserAgentRegex = Regex(
            "(bot|crawler|spider|headless|slurp|preview|curl|wget|python-requests)",
            RegexOption.IGNORE_CASE
        )
    }

    override val paths = listOf(Path("/api/posts/:url/view", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("url", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val url = parameters.pathParameter("url").string

        val sqlClient = getSqlClient()
        val post = databaseManager.postDao.getByUrl(url, sqlClient) ?: throw PostNotFound()

        // Ignore obvious non-human clients for view analytics.
        if (isLikelyBot(context)) {
            return Successful(mapOf("counted" to false))
        }

        val viewerHash = createViewerHash(context)
        val now = System.currentTimeMillis()

        val counted = databaseManager.postViewTrackerDao.tryRecordView(
            postId = post.id,
            viewerHash = viewerHash,
            viewedAt = now,
            dedupeWindowMs = DEDUPE_WINDOW_MS,
            sqlClient = sqlClient
        )

        if (counted) {
            databaseManager.postDao.increaseViewByOne(post.id, sqlClient)
        }

        return Successful(mapOf("counted" to counted))
    }

    private suspend fun createViewerHash(context: RoutingContext): String {
        val isLoggedIn = authProvider.isLoggedIn(context)

        val viewerKey = if (isLoggedIn) {
            "auth:${authProvider.getUserIdFromRoutingContext(context)}"
        } else {
            val viewerId = context.request().getHeader(VIEWER_ID_HEADER)
                ?.trim()
                ?.takeIf { viewerIdRegex.matches(it) }
                ?: "anonymous"

            val ipPrefix = getIpPrefix(authProvider.getRemoteIP(context))
            val userAgentHash = sha256(getNormalizedUserAgent(context))

            "anon:$viewerId|$ipPrefix|$userAgentHash"
        }

        return sha256(viewerKey)
    }

    private fun isLikelyBot(context: RoutingContext): Boolean {
        val userAgent = context.request().getHeader("User-Agent") ?: return false
        return botUserAgentRegex.containsMatchIn(userAgent)
    }

    private fun getNormalizedUserAgent(context: RoutingContext): String {
        val userAgent = context.request().getHeader("User-Agent")
            ?.trim()
            ?.lowercase()
            ?: ""

        return userAgent.take(USER_AGENT_MAX_LENGTH)
    }

    private fun getIpPrefix(ipAddress: String): String {
        val normalizedIp = ipAddress.trim()

        if (normalizedIp.contains(":")) {
            // IPv6: use /64-equivalent prefix for less sensitive storage.
            return normalizedIp
                .split(":")
                .map { it.ifEmpty { "0" } }
                .take(4)
                .joinToString(":")
        }

        val ipParts = normalizedIp.split(".")
        if (ipParts.size == 4) {
            return "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}.0"
        }

        return "unknown"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
