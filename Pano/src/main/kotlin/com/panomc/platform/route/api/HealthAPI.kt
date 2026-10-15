package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class HealthAPI : Api() {
    override val paths = listOf(Path("/health", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Answers 200 with an empty object while Pano is running; no login, never gated by setup or maintenance.",
        tag = "health",
        response = objectSchema()
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun onBeforeHandle(context: RoutingContext) {}

    override suspend fun handle(context: RoutingContext): Result {
        return Successful()
    }
}