package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.webhook.WebhookInFlight
import com.panomc.platform.webhook.WebhookNotFound
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `POST /webhook-deliveries/:id/redeliver`: the same row back to `PENDING` (same event id); `WEBHOOK_IN_FLIGHT` while it is `SENDING`. */
@Endpoint
class PanelRedeliverWebhookAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhook-deliveries/:id/redeliver", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Queues a webhook delivery again.", tag = "webhooks", errors = listOf(WebhookNotFound::class, WebhookInFlight::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = idValidation(schemaRepository)

    override suspend fun execute(context: RoutingContext): Result {
        val id = idOf(context)
        val name = service.nameOfDelivery(id) ?: "#$id"

        service.redeliver(id)

        record(context) { userId, username -> RedeliveredWebhookLog(userId, username, name) }

        return Successful()
    }
}
