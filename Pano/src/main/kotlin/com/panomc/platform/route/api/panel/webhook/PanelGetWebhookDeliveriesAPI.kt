package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `GET /webhook-deliveries?source=&status=&page=&pageSize=`: the whole log, newest first, `{ items, page }`. */
@Endpoint
class PanelGetWebhookDeliveriesAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhook-deliveries", RouteType.GET))

    override val doc = EndpointDoc(summary = "The webhook delivery log, filtered by source and status.", tag = "webhooks")

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("source", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun execute(context: RoutingContext): Result {
        val parameters = getParameters(context)

        return Successful(
            service.deliveries(
                null, parameters.queryParameter("source")?.string, parameters.queryParameter("status")?.string, Paging.request(context)
            )
        )
    }
}
