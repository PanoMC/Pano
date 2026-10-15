package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.webhook.WebhookHeadersInvalid
import com.panomc.platform.webhook.WebhookLimit
import com.panomc.platform.webhook.WebhookUnknownEvent
import com.panomc.platform.webhook.WebhookUrlRefused
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `POST /webhooks`: `{ name, url, events[], format?, signing?, headers?, template?, enabled?, maxAttempts? }` creates an
 * endpoint and answers `{ id, secret? }`; a generated HMAC secret is in the answer once and never again.
 */
@Endpoint
class PanelCreateWebhookAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Creates a webhook endpoint; the generated signing secret is shown once.",
        tag = "webhooks",
        errors = listOf(WebhookUrlRefused::class, WebhookLimit::class, WebhookUnknownEvent::class, WebhookHeadersInvalid::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        bodyValidation(schemaRepository, withId = false)

    override suspend fun execute(context: RoutingContext): Result {
        val saved = service.create(getParameters(context).body().jsonObject ?: JsonObject())

        record(context) { userId, username -> CreatedWebhookLog(userId, username, nameOfBody(context)) }

        return Successful(saved.secret?.let { mapOf("id" to saved.id, "secret" to it) } ?: mapOf("id" to saved.id))
    }
}
