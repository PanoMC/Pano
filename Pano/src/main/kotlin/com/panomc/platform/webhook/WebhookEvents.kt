package com.panomc.platform.webhook

import io.vertx.core.json.JsonArray
import java.util.UUID

/**
 * Event names of core webhooks (doc 06 §4.2): `<source>.<subject>.<verb>[.<qualifier>]`, segments `[a-z0-9_-]`,
 * `<source>` = `core` or the plugin's namespace.
 */
object WebhookEvents {
    const val CORE = "core"
    const val WILDCARD = "*"
    const val API_VERSION = 1

    const val USER_REGISTERED = "core.user.registered"
    const val USER_DELETED = "core.user.deleted"
    const val TICKET_CREATED = "core.ticket.created"
    const val TICKET_REPLIED = "core.ticket.replied"
    const val POST_PUBLISHED = "core.post.published"

    /** The panel's test button. Never matched by a wildcard, never subscribable. */
    const val TEST_PING = "core.test.ping"

    private val SEGMENT = Regex("^[a-z0-9_-]+$")

    /** Longest full event name a delivery row stores. */
    const val MAX_EVENT_LENGTH = 128

    /** Longest event name without its source (a 64 character source, a dot and this fit in [MAX_EVENT_LENGTH]). */
    const val MAX_NAME_LENGTH = 63

    /** A source is one segment: `core` or a namespace. */
    fun isValidSource(source: String): Boolean = source.length <= 64 && SEGMENT.matches(source)

    /** [name] without its source: one or more dot-separated segments of `[a-z0-9_-]`. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME_LENGTH && name.split('.').all { SEGMENT.matches(it) }

    /** The full event name of [name] published by [source]. */
    fun full(source: String, name: String): String = "$source.$name"

    /** The source of a full event name. */
    fun sourceOf(event: String): String = event.substringBefore('.')

    /** The name without the source of a full event name. */
    fun nameOf(event: String): String = event.substringAfter('.', event)

    /**
     * `true` when the endpoint's `events` JSON list covers [event]: the exact name, `<source>.*`, or `*`. A list
     * that is not a JSON array of strings matches nothing (an unreadable subscription must never become
     * "everything"). Wildcards never cover [TEST_PING] or an event that is not [subscribable].
     */
    fun matches(eventsJson: String, event: String, subscribable: Boolean = true): Boolean {
        val names = try {
            JsonArray(eventsJson).list.map { it as? String ?: return false }
        } catch (e: Exception) {
            return false
        }

        if (event in names) return true
        if (!subscribable || event == TEST_PING) return false

        if (WILDCARD in names) return true

        val source = sourceOf(event)

        return "$source.*" in names
    }

    /**
     * The deterministic event id: the same transition replayed for the same endpoint gives the same id, so the
     * unique index turns the second insert into a no-op. It is also the receiver's de-duplication key. The full
     * name carries the source, so two plugins cannot collide.
     */
    fun eventId(event: String, subjectKey: String, endpointId: Long): String =
        UUID.nameUUIDFromBytes("$event:$subjectKey:$endpointId".toByteArray(Charsets.UTF_8)).toString()
}
