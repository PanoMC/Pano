package com.panomc.platform.route.api.panel.platformAuth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.PlatformCodeManager
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class RefreshKeyAPI(
    private val platformCodeManager: PlatformCodeManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/platformAuth/refreshKey")

    override suspend fun handler(context: RoutingContext) = Successful(
        mapOf(
            "key" to platformCodeManager.getPlatformKey(),
            "timeStarted" to platformCodeManager.getTimeStarted()
        )
    )
}