package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.webhook.WebhookNotFound
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /webhook-deliveries/:id`: one delivery with the stored body and the last response. The signing secret is never in it. */
@Endpoint
class PanelGetWebhookDeliveryAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhook-deliveries/:id", RouteType.GET))

    override val doc = EndpointDoc(summary = "One webhook delivery with its body and last response.", tag = "webhooks", errors = listOf(WebhookNotFound::class))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = idValidation(schemaRepository)

    override suspend fun execute(context: RoutingContext): Result = Successful(service.delivery(idOf(context)))
}
