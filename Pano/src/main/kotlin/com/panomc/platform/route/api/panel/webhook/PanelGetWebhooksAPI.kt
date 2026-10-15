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

/** `GET /webhooks`: `{ items: [endpoint], limit: 50 }`; the secret and every header value are masked. */
@Endpoint
class PanelGetWebhooksAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks", RouteType.GET))

    override val doc = EndpointDoc(summary = "The webhook endpoints of the site (secrets masked).", tag = "webhooks")

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun execute(context: RoutingContext): Result = Successful(service.list())
}
