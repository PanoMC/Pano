package com.panomc.platform.route.api.setup.step

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser

@Endpoint
class Step4DisconnectPlatformAPI(
    private val panoApiManager: PanoApiManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/steps/4/platform/disconnect", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        panoApiManager.disconnectPlatform()

        return Successful()
    }
}