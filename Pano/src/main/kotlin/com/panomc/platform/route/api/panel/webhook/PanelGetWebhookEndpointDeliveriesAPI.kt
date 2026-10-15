package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.webhook.WebhookNotFound
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `GET /webhooks/:id/deliveries?status=&page=&pageSize=`: the log of one endpoint, newest first, `{ items, page }`. */
@Endpoint
class PanelGetWebhookEndpointDeliveriesAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/:id/deliveries", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The delivery log of one webhook endpoint.", tag = "webhooks", errors = listOf(WebhookNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository).pathParameter(param("id", numberSchema())))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun execute(context: RoutingContext): Result {
        val status = getParameters(context).queryParameter("status")?.string

        return Successful(service.deliveries(idOf(context), null, status, Paging.request(context)))
    }
}
