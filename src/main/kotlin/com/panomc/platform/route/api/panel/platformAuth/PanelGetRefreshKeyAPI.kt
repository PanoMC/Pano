package com.panomc.platform.route.api.panel.platformAuth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.PlatformCodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetRefreshKeyAPI(
    private val platformCodeManager: PlatformCodeManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/platformAuth/refreshKey", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_SERVERS, context)

        return Successful(
            mapOf(
                "key" to platformCodeManager.getPlatformKey(),
                "timeStarted" to platformCodeManager.getTimeStarted()
            )
        )
    }
}