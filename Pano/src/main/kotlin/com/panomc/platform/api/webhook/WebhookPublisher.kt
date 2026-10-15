package com.panomc.platform.api.webhook

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.webhook.Decision
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * Core webhooks for plugins (open front-end plan, doc 06 §4.3). A core bean every plugin can ask for:
 *
 * ```kotlin
 * val webhooks = webhookPublisher()   // PanoPlugin.webhookPublisher()
 * ```
 *
 * `event` is always the name **without** the source: core adds the plugin's namespace
 * (`PluginNamespace.of(plugin)`), so `order.paid` of the plugin `pano-plugin-market` is `market.order.paid`. A
 * plugin cannot emit `core.*` events. Segments are `[a-z0-9_-]`, separated by dots.
 *
 * An event the plugin did not declare with [register] is declared on its first [publish]. What [register]
 * declared is dropped when the plugin stops.
 *
 * Nothing here sends: [publish] and [enqueueDirect] only write rows, on the connection the caller passes (its
 * transaction: the rows commit or roll back with it) or, with `null`, on core's pool. The dispatcher sends them.
 * None of them throws for what an endpoint contains; a name that breaks the rules above throws
 * [IllegalArgumentException].
 */
interface WebhookPublisher {
    /**
     * One `PENDING` row per enabled endpoint whose subscription matches the event, written on [sqlClient]
     * (the caller's transaction; `null` = the pool). Returns how many rows were new: a replay of the same
     * `(event, subjectKey)` for the same endpoint inserts nothing.
     *
     * [subjectKey] makes the event id deterministic (`UUID` of `"<full event>:<subjectKey>:<endpointId>"`), so
     * two plugins that publish the same event name for the same subject never collide. [data] is the `data` object of
     * the envelope; [extra] adds top-level keys to it and may not shadow `id`, `event`, `source`, `createdAt`,
     * `apiVersion`, `site` or `data`. [subjectRef] is shown in the delivery log (`order:12`).
     */
    suspend fun publish(
        plugin: PanoPlugin, event: String, subjectKey: String, data: JsonObject,
        sqlClient: SqlClient? = null, subjectRef: String? = null, extra: JsonObject? = null
    ): Int

    /**
     * `true` when at least one enabled endpoint would receive [event]: build an expensive `data` object only
     * when it is. Reads on [sqlClient] so it sees the caller's own writes.
     */
    suspend fun hasListeners(plugin: PanoPlugin, event: String, sqlClient: SqlClient? = null): Boolean

    /**
     * Optional: declares events up front (panel catalogue, sample body), a Discord renderer for the plugin's
     * events and a listener for the end of its direct deliveries. Calling it again replaces the earlier call of
     * the same plugin. Dropped when the plugin stops.
     */
    fun register(
        plugin: PanoPlugin, events: List<WebhookEventType>, discord: WebhookDiscordRenderer? = null,
        outcomes: WebhookOutcomeListener? = null
    )

    /**
     * A delivery to a URL the plugin owns (no endpoint): same signing, retry and log. Returns the row id, or
     * `null` when [DirectWebhook.eventId] was queued before (nothing is inserted). While the plugin is stopped its
     * direct rows are not claimed.
     */
    suspend fun enqueueDirect(plugin: PanoPlugin, direct: DirectWebhook, sqlClient: SqlClient? = null): Long?
}

/**
 * An event a plugin declares. [name] is without the source. [sample] is the `data` the panel shows as an example.
 * With [subscribable] `false` no endpoint can subscribe to it and no wildcard matches it.
 */
class WebhookEventType(val name: String, val sample: JsonObject? = null, val subscribable: Boolean = true)

/**
 * A delivery to a URL the plugin owns. [event] is without the source (core adds it); [eventId] must be unique
 * (a second [WebhookPublisher.enqueueDirect] with the same id inserts nothing); [body] is sent as it is, as
 * `application/json`. With [signing] `HMAC_SHA256` the [secret] signs the request and is stored encrypted.
 * [ownerRef] is the plugin's own reference to what the delivery is for; the outcome listener gets it back.
 */
class DirectWebhook(
    val url: String,
    val event: String,
    val eventId: String,
    val body: String,
    val signing: WebhookSigning,
    val secret: String?,
    val maxAttempts: Int = 8,
    val ownerRef: String
)

/** A rendered Discord body and the warning to record in the delivery log, if any. */
class RenderedBody(val body: String, val warning: String? = null)

/**
 * Renders the body for an endpoint with `format = DISCORD`. [event] is the name without the source, as the plugin
 * published it; [envelope] is the full JSON envelope; [template] is the endpoint's template text, if any. May
 * throw: the delivery then ends `DEAD (RENDER_FAILED)`.
 */
fun interface WebhookDiscordRenderer {
    suspend fun render(event: String, envelope: JsonObject, template: String?): RenderedBody
}

/**
 * Called on the connection of the transaction that stores the end (`SUCCEEDED` or `DEAD`) of a **direct**
 * delivery of the plugin. If it throws, the row stays `SENDING` and is retried when its claim expires.
 */
fun interface WebhookOutcomeListener {
    suspend fun onOutcome(sqlClient: SqlClient, delivery: WebhookDelivery, decision: Decision)
}

/** The core webhook publisher, for a plugin's `onStart`. */
fun PanoPlugin.webhookPublisher(): WebhookPublisher = applicationContext.getBean(WebhookPublisher::class.java)
