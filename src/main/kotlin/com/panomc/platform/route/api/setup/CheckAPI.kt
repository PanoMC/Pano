package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class CheckAPI(
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/setup/step/check")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        return Successful(setupManager.getCurrentStepData().map)
    }
}