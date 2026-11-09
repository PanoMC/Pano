package com.panomc.platform.route.api.setup.step

import com.panomc.platform.setup.SetupAccountSession

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class GetCurrentStepAPI(private val setupAccountSession: SetupAccountSession) : SetupApi() {
    override val paths = listOf(Path("/setup/step", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val stepData = setupManager.getCurrentStepData().map.toMutableMap()
        // The connected account is shown only to the browser that connected it.
        if (!setupAccountSession.matches(context)) stepData.remove("panoAccount")
        stepData["version"] = Main.VERSION
        stepData["stage"] = Main.STAGE

        return Successful(stepData)
    }
}