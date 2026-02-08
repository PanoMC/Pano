package com.panomc.platform.route.api.setup.step

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetCurrentStepAPI : SetupApi() {
    override val paths = listOf(Path("/api/setup/step", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val stepData = setupManager.getCurrentStepData().map.toMutableMap()
        stepData["version"] = Main.VERSION
        stepData["stage"] = Main.STAGE

        return Successful(stepData)
    }
}