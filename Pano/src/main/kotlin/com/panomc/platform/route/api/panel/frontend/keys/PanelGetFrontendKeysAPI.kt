package com.panomc.platform.route.api.panel.frontend.keys

import com.panomc.platform.access.FrontendKeyService
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext

/** Lists the front-end keys (never the keys themselves: only a hint, the last four characters). */
@Endpoint
class PanelGetFrontendKeysAPI(
    private val frontendKeyService: FrontendKeyService,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/keys", RouteType.GET))

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val keys = frontendKeyService.list(getSqlClient())

        return Successful(
            mapOf(
                "items" to keys.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "hint" to it.keyHint,
                        "createdAt" to it.createdAt,
                        "lastUsedAt" to it.lastUsedAt
                    )
                },
                "max" to FrontendKeyService.MAX_KEYS
            )
        )
    }
}
