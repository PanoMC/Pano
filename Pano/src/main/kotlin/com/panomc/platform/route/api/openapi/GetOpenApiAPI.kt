package com.panomc.platform.route.api.openapi

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.RouteTable
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.OpenApiGenerator
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/openapi.json` -- the OpenAPI 3.1 document of the public core API (doc 04 section 5), built from the
 * route table of this running Pano. A client generator and the snapshot tool read it; it holds no secret, so it is
 * served in maintenance mode too.
 */
@Endpoint
class GetOpenApiAPI(private val routeTable: RouteTable) : Api() {
    override val paths = listOf(Path("/openapi.json", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc: EndpointDoc = OpenApiResponseDoc.of("The OpenAPI 3.1 document of the public core API")

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result =
        Successful(OpenApiGenerator.generate(OpenApiGenerator.Scope.Core, routeTable.entries()).map)
}
