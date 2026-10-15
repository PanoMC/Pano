package com.panomc.platform.webhook

import com.panomc.platform.PluginManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.api.webhook.WebhookOutcomeListener
import com.panomc.platform.plugin.PluginNamespace
import io.vertx.core.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** One event of the catalogue the panel shows: [name] is the full name (`market.order.paid`). */
class CatalogueEvent(val name: String, val subscribable: Boolean, val sample: JsonObject?)

/** All events of one [source], sorted by name. */
class CatalogueSource(val source: String, val events: List<CatalogueEvent>)

/**
 * What the running plugins declared (doc 06 §4.3): their events, Discord renderer and outcome listener, by
 * source. Dropped when a plugin stops. The core events are always there.
 *
 * It listens to the plugin lifecycle by itself (`PluginManager.addLifecycleListener`), so nothing outside the
 * webhook package has to call it.
 */
class WebhookRegistry(pluginManager: PluginManager?) : PluginLifecycleListener {
    private class Event(val subscribable: Boolean, val sample: JsonObject?, val declared: Boolean)

    private class Source {
        val events = ConcurrentHashMap<String, Event>()

        @Volatile
        var discord: WebhookDiscordRenderer? = null

        @Volatile
        var outcomes: WebhookOutcomeListener? = null
    }

    private val sources = ConcurrentHashMap<String, Source>()

    init {
        val core = sources.computeIfAbsent(WebhookEvents.CORE) { Source() }

        fun core(name: String, sample: JsonObject, subscribable: Boolean = true) {
            core.events[name] = Event(subscribable, sample, declared = true)
        }

        core(
            WebhookEvents.USER_REGISTERED,
            JsonObject().put("id", 12).put("username", "Steve").put("registeredAt", 1_700_000_000_000L)
        )
        core(WebhookEvents.USER_DELETED, JsonObject().put("id", 12).put("username", "Steve"))
        core(
            WebhookEvents.TICKET_CREATED,
            JsonObject().put("id", 7).put("title", "Help with my rank").put("categoryId", 1).put("userId", 12)
                .put("username", "Steve")
        )
        core(
            WebhookEvents.TICKET_REPLIED,
            JsonObject().put("ticketId", 7).put("messageId", 31).put("userId", 3).put("staff", true)
        )
        core(
            WebhookEvents.POST_PUBLISHED,
            JsonObject().put("id", 4).put("title", "Server news").put("url", "https://example.com/post/4-server-news")
                .put("categoryId", 1).put("publishedAt", 1_700_000_000_000L)
        )
        core(WebhookEvents.TEST_PING, JsonObject().put("message", "ping"), subscribable = false)

        pluginManager?.addLifecycleListener(this)
    }

    /** Replaces everything [source] declared before: its events, renderer and listener. */
    fun register(
        source: String, events: List<WebhookEventType>, discord: WebhookDiscordRenderer?, outcomes: WebhookOutcomeListener?
    ) {
        require(source != WebhookEvents.CORE) { "core events are not declared by a plugin" }

        val entry = sources.computeIfAbsent(source) { Source() }

        entry.events.entries.removeIf { it.value.declared }

        for (type in events) {
            entry.events[WebhookEvents.full(source, type.name)] = Event(type.subscribable, type.sample, declared = true)
        }

        entry.discord = discord
        entry.outcomes = outcomes
    }

    /** Declares [name] (without the source) of [source] when nobody did: subscribable, no sample. */
    fun ensure(source: String, name: String) {
        val entry = sources.computeIfAbsent(source) { Source() }

        entry.events.computeIfAbsent(WebhookEvents.full(source, name)) { Event(true, null, declared = false) }
    }

    /** `false` only for an event that was declared `subscribable = false` (and `core.test.ping`). */
    fun isSubscribable(event: String): Boolean =
        sources[WebhookEvents.sourceOf(event)]?.events?.get(event)?.subscribable ?: true

    /** `true` when [event] (full name) is in the catalogue. */
    fun isKnown(event: String): Boolean = sources[WebhookEvents.sourceOf(event)]?.events?.containsKey(event) == true

    fun discord(source: String): WebhookDiscordRenderer? = sources[source]?.discord

    fun outcomes(source: String): WebhookOutcomeListener? = sources[source]?.outcomes

    /** The sample `data` of a declared event, or `null`. */
    fun sample(event: String): JsonObject? = sources[WebhookEvents.sourceOf(event)]?.events?.get(event)?.sample

    /** Core first, then the plugins by source; the events of a source by name. */
    fun catalogue(): List<CatalogueSource> =
        sources.entries
            .sortedWith(compareBy({ it.key != WebhookEvents.CORE }, { it.key }))
            .map { (source, entry) ->
                CatalogueSource(
                    source,
                    entry.events.entries.sortedBy { it.key }.map { CatalogueEvent(it.key, it.value.subscribable, it.value.sample) }
                )
            }
            .filter { it.events.isNotEmpty() }

    /** Forgets everything a plugin declared (it stopped). The core events stay. */
    fun unregister(source: String) {
        if (source != WebhookEvents.CORE) sources.remove(source)
    }

    override suspend fun onPluginDisable(plugin: PanoPlugin) {
        unregister(PluginNamespace.of(plugin))
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        unregister(PluginNamespace.of(plugin))
    }
}
