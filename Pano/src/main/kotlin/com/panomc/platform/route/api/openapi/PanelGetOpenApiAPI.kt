package com.panomc.platform.route.api.openapi

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.PanelApi
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
 * `GET /api/v1/panel/openapi.json` -- the OpenAPI 3.1 document of every internal operation of core and of all
 * plugins (doc 04 section 5). Internal operations carry no compatibility promise, so this is for the panel's own
 * tooling and the snapshot check, and needs a panel session.
 */
@Endpoint
class PanelGetOpenApiAPI(private val routeTable: RouteTable) : PanelApi() {
    override val paths = listOf(Path("/openapi.json", RouteType.GET))

    override val doc: EndpointDoc = OpenApiResponseDoc.of("The OpenAPI 3.1 document of the internal API")

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result =
        Successful(OpenApiGenerator.generate(OpenApiGenerator.Scope.Internal, routeTable.entries()).map)
}
