package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class CheckAPI(
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val paths = listOf(Path("/api/setup/step/check", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        return Successful(setupManager.getCurrentStepData().map)
    }
}