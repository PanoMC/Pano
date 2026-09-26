package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.hosted.HostNotice
import com.panomc.platform.hosted.HostNoticeFeed
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import org.slf4j.LoggerFactory

/**
 * Pano Host info for the panel's "managed by Pano Host" link and notice banner:
 * `{hosted, workloadId, manageUrl, notices[]}`. Any panel user may read it; outside Pano Host it
 * answers `hosted: false` with no notices.
 */
@Endpoint
class PanelGetHostedAPI(private val noticeFeed: HostNoticeFeed) : PanelApi() {
    override val paths = listOf(Path("/api/panel/hosted", RouteType.GET))

    /** Swappable for tests. */
    internal var env: HostedEnvConfig = HostedEnvConfig.current

    private val log = LoggerFactory.getLogger(PanelGetHostedAPI::class.java)

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result = Successful(info())

    internal suspend fun info(): Map<String, Any?> {
        val env = env

        if (!env.isHosted) {
            return mapOf("hosted" to false, "workloadId" to null, "manageUrl" to null, "notices" to emptyList<Any>())
        }

        val notices = try {
            noticeFeed.notices()
        } catch (e: Exception) {
            log.warn("Pano Host notice feed failed: {}", e.javaClass.simpleName)
            emptyList()
        }

        return mapOf(
            "hosted" to true,
            "workloadId" to env.workloadId,
            "manageUrl" to env.manageUrl,
            "notices" to notices.mapNotNull { it.sanitized()?.toMap() }
        )
    }

    private fun HostNotice.sanitized(): HostNotice? {
        if (id.isBlank() || message.isBlank()) return null

        return copy(
            level = level.lowercase().takeIf { it in HostNotice.LEVELS } ?: "info",
            url = url?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        )
    }
}
