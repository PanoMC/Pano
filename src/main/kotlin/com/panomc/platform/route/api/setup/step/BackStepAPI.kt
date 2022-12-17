package com.panomc.platform.route.api.setup.step

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class BackStepAPI(
    private val setupManager: SetupManager
) : SetupApi(setupManager) {
    override val paths = listOf(Path("/api/setup/step/backStep", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handler(context: RoutingContext): Result {
        setupManager.backStep()

        return Successful(setupManager.getCurrentStepData().map)
    }
}