package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Where each page that Pano links to lives on this site (doc 05 section 10.1): the public read of the
 * front-end URL map. A path keeps its `{name}` placeholders (`/activate?token={token}`); a target
 * nothing serves is left out. A headless front-end uses it to build its own links.
 */
@Endpoint
class GetFrontendUrlsAPI(private val frontendUrlMap: FrontendUrlMap) : Api() {
    override val paths = listOf(Path("/frontend/urls", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Where each page Pano links to lives on the site; paths keep their {name} placeholders.",
        tag = "frontend",
        response = objectSchema()
            .requiredProperty("siteUrl", stringSchema())
            .requiredProperty("urls", objectSchema().additionalProperties(stringSchema()))
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val urls = linkedMapOf<String, String>()

        frontendUrlMap.targets().forEach { target ->
            target.path?.let { urls[target.id] = it }
        }

        return Successful(mapOf("siteUrl" to frontendUrlMap.siteUrl(), "urls" to urls))
    }
}
