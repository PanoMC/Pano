package com.panomc.platform.route.api.panel.frontend.origins

import com.panomc.platform.access.OriginPolicy
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext

/** The browser origins allowed to call the API with the visitor's cookie (doc 05 §5). */
@Endpoint
class PanelGetFrontendOriginsAPI(
    private val originPolicy: OriginPolicy,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/origins", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        return Successful(
            mapOf(
                "origins" to originPolicy.list(getSqlClient()),
                "max" to OriginPolicy.MAX_ORIGINS
            )
        )
    }
}
