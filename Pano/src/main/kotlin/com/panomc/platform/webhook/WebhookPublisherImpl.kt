package com.panomc.platform.webhook

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.webhook.DirectWebhook
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.api.webhook.WebhookOutcomeListener
import com.panomc.platform.api.webhook.WebhookPublisher
import com.panomc.platform.plugin.PluginNamespace
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * The [WebhookPublisher] plugins get. It only translates: the plugin becomes its namespace (`PluginNamespace.of`), the
 * rest is [WebhookService]. Every call makes sure the [dispatcher] runs.
 */
class WebhookPublisherImpl(
    private val service: WebhookService,
    private val registry: WebhookRegistry,
    private val dispatcher: WebhookDispatcher,
    private val namespaceOf: (PanoPlugin) -> String = { PluginNamespace.of(it) }
) : WebhookPublisher {
    override suspend fun publish(
        plugin: PanoPlugin, event: String, subjectKey: String, data: JsonObject,
        sqlClient: SqlClient?, subjectRef: String?, extra: JsonObject?
    ): Int {
        dispatcher.start()

        return service.publish(namespaceOf(plugin), event, subjectKey, data, sqlClient, subjectRef, extra)
    }

    override suspend fun hasListeners(plugin: PanoPlugin, event: String, sqlClient: SqlClient?): Boolean =
        service.hasListeners(namespaceOf(plugin), event, sqlClient)

    override fun register(
        plugin: PanoPlugin, events: List<WebhookEventType>, discord: WebhookDiscordRenderer?, outcomes: WebhookOutcomeListener?
    ) {
        val source = namespaceOf(plugin)

        require(source != WebhookEvents.CORE) { "a plugin cannot declare core events" }
        require(WebhookEvents.isValidSource(source)) { "invalid webhook source: $source" }

        for (type in events) {
            require(WebhookEvents.isValidName(type.name)) { "invalid webhook event name: ${type.name}" }
        }

        registry.register(source, events, discord, outcomes)
        dispatcher.start()
    }

    override suspend fun enqueueDirect(plugin: PanoPlugin, direct: DirectWebhook, sqlClient: SqlClient?): Long? {
        dispatcher.start()

        return service.enqueueDirect(namespaceOf(plugin), direct, sqlClient)
    }
}
