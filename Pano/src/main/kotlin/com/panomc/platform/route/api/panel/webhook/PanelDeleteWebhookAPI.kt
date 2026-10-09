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

/** `DELETE /webhooks/:id`: a hard delete; the rows still open become `DEAD (ENDPOINT_DELETED)` first. */
@Endpoint
class PanelDeleteWebhookAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/:id", RouteType.DELETE))

    override val doc = EndpointDoc(summary = "Deletes a webhook endpoint.", tag = "webhooks", errors = listOf(WebhookNotFound::class))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = idValidation(schemaRepository)

    override suspend fun execute(context: RoutingContext): Result {
        val id = idOf(context)
        val name = service.nameOf(id) ?: "#$id"

        service.delete(id)

        record(context) { userId, username -> DeletedWebhookLog(userId, username, name) }

        return Successful()
    }
}
