package com.panomc.platform.webhook.template

import com.panomc.platform.webhook.WebhookEvents
import io.vertx.core.json.JsonObject

/**
 * The `templates` column of a Discord endpoint: a JSON object from an event pattern to the template of that event.
 * A pattern is a full event name (`core.ticket.created`), `<source>.*` or `*`; the most specific one wins.
 */
object WebhookTemplates {
    const val MAX_TEMPLATE = 20_000
    const val MAX_ENTRIES = 200

    /** The stored map, or an empty one when the column is empty or unreadable. */
    fun parse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()

        return runCatching {
            val obj = JsonObject(json)

            obj.fieldNames().mapNotNull { key -> (obj.getValue(key) as? String)?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** The column value of [templates]: `null` when there is none. */
    fun encode(templates: Map<String, String>): String? =
        templates.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { JsonObject(it.toMutableMap<String, Any?>()).encode() }

    /** The template for [event] (full name): its own, else its source's `<source>.*`, else `*`, else `null`. */
    fun resolve(templates: Map<String, String>, event: String): String? =
        templates[event] ?: templates["${WebhookEvents.sourceOf(event)}.*"] ?: templates[WebhookEvents.WILDCARD]

    /** As [resolve] for the stored column. */
    fun resolve(json: String?, event: String): String? = resolve(parse(json), event)

    /** `true` for `*`, `<source>.*` and a well-formed full event name. */
    fun isValidKey(key: String): Boolean {
        if (key == WebhookEvents.WILDCARD) return true

        if (key.endsWith(".*")) return WebhookEvents.isValidSource(key.removeSuffix(".*"))

        val source = WebhookEvents.sourceOf(key)
        val name = WebhookEvents.nameOf(key)

        return key.length <= WebhookEvents.MAX_EVENT_LENGTH && key.contains('.') &&
                WebhookEvents.isValidSource(source) && WebhookEvents.isValidName(name)
    }
}
