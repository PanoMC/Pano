package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.webhook.WebhookHeadersInvalid
import com.panomc.platform.webhook.WebhookNotFound
import com.panomc.platform.webhook.WebhookUnknownEvent
import com.panomc.platform.webhook.WebhookUrlRefused
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `PUT /webhooks/:id`: the fields of the create body (a missing field keeps its value) plus `regenerateSecret`;
 * answers `{ id, secret? }` where `secret` is only present when a new one was generated. A header value of `********`
 * keeps the stored value of that header.
 */
@Endpoint
class PanelUpdateWebhookAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/:id", RouteType.PUT))

    override val doc = EndpointDoc(
        summary = "Updates a webhook endpoint.",
        tag = "webhooks",
        errors = listOf(WebhookNotFound::class, WebhookUrlRefused::class, WebhookUnknownEvent::class, WebhookHeadersInvalid::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        bodyValidation(schemaRepository, withId = true)

    override suspend fun execute(context: RoutingContext): Result {
        val saved = service.update(idOf(context), getParameters(context).body().jsonObject ?: JsonObject())
        val name = service.nameOf(saved.id) ?: nameOfBody(context)

        record(context) { userId, username -> UpdatedWebhookLog(userId, username, name) }

        return Successful(saved.secret?.let { mapOf("id" to saved.id, "secret" to it) } ?: mapOf("id" to saved.id))
    }
}
