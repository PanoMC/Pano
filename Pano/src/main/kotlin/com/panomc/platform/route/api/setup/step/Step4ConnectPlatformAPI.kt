package com.panomc.platform.route.api.setup.step

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class Step4ConnectPlatformAPI(
    private val panoApiManager: PanoApiManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/steps/4/platform/connect", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("encodedData", stringSchema())
                        .requiredProperty("state", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val encodedData = data.getString("encodedData")
        val state = data.getString("state")

        val (username, email, platformId) = panoApiManager.connectPlatform(encodedData, state)

        return Successful(
            mapOf(
                "username" to username,
                "email" to email,
                "platformId" to platformId,
            )
        )
    }
}