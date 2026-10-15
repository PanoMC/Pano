package com.panomc.platform.route.api.panel.frontend.urls

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext

/**
 * The front-end URL map for the panel (doc 05 section 10.1): the admin's overrides and every target
 * with the step that answered for it.
 */
@Endpoint
class PanelGetFrontendUrlsAPI(
    private val frontendUrlMap: FrontendUrlMap,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/urls", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        return Successful(frontendUrlsState(frontendUrlMap))
    }
}

/** The body of `GET /panel/frontend/urls` and of a successful `PUT`. */
internal fun frontendUrlsState(frontendUrlMap: FrontendUrlMap): Map<String, Any?> = mapOf(
    "siteUrl" to frontendUrlMap.siteUrl(),
    "overrides" to frontendUrlMap.overrides(),
    "targets" to frontendUrlMap.targets().map { target ->
        mapOf(
            "id" to target.id,
            "owner" to target.owner,
            "source" to target.source?.name,
            "path" to target.path,
            "defaultPath" to target.defaultPath,
            "fallback" to target.fallback,
            "createsSession" to target.createsSession
        )
    }
)
