package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /webhooks/events`: the catalogue of core and running plugins, `{ items: [{ source, title, events: [{ name, subscribable, sample }] }] }`. */
@Endpoint
class PanelGetWebhookEventsAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/events", RouteType.GET))

    override val doc = EndpointDoc(summary = "The events an endpoint can subscribe to, by source.", tag = "webhooks")

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun execute(context: RoutingContext): Result = Successful(service.events())
}
