package com.panomc.platform.route.api.setup.step

import com.panomc.platform.setup.SetupAccountSession

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class Step4CreatePlatformConnectCodeAPI(
    private val panoApiManager: PanoApiManager,
    private val setupAccountSession: SetupAccountSession
) : SetupApi() {
    override val paths = listOf(Path("/setup/steps/4/platform/code", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val (publicKey, state) = panoApiManager.createPanoCode()

        // A new code drops the connected account, and with it the browser bound to it.
        setupAccountSession.clear()

        return Successful(
            mapOf(
                "publicKey" to publicKey,
                "state" to state
            )
        )
    }
}