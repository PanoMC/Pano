package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class CheckAPI(
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/setup/step/check")

    override suspend fun handler(context: RoutingContext): Result {
        return Successful(setupManager.getCurrentStepData().map)
    }
}