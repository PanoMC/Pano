package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class BackStepAPI(
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/step/backStep")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        setupManager.backStep()

        handler.invoke(Successful(setupManager.getCurrentStepData().map))
    }
}