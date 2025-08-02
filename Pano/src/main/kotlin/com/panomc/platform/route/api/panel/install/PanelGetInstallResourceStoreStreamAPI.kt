package com.panomc.platform.route.api.panel.install

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.*

@Endpoint
class PanelGetInstallResourceStoreStreamAPI(
    private val panoApiManager: PanoApiManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/install/store/:versionId/stream", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("versionId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val versionId = try {
            UUID.fromString(parameters.pathParameter("versionId").string)
        } catch (_: Exception) {
            throw BadRequest()
        }

        val response = context.response()

        response.putHeader("Content-Type", "text/event-stream")
        response.putHeader("Cache-Control", "no-cache")
        response.putHeader("Connection", "keep-alive")
        response.isChunked = true

        panoApiManager.installResourceFromStore(context, versionId)

        return null
    }
}