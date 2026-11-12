package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `POST /webhooks/preview` `{ event, template? }`: the Discord body the template gives for the event's sample data,
 * `{ body, warning }`. Nothing is sent and nothing is stored; the panel's template editor calls it while typing.
 */
@Endpoint
class PanelPreviewWebhookTemplateAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/preview", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Renders a Discord webhook template with the sample data of an event.",
        tag = "webhooks",
        errors = listOf(RateLimited::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository, withId = false)

    override suspend fun execute(context: RoutingContext): Result {
        if (!limiter.tryAcquire("user:${userIdOf(context)}")) throw RateLimited(extras = mapOf("retryAfter" to WINDOW_MS / 1000))

        return Successful(service.preview(getParameters(context).body().jsonObject))
    }

    /** The editor previews while typing (debounced): 120 a minute per panel user. */
    private val limiter = SlidingWindowLimiter(PER_MINUTE, WINDOW_MS, System::currentTimeMillis)

    internal companion object {
        const val PER_MINUTE = 120
        const val WINDOW_MS = 60_000L
    }
}
