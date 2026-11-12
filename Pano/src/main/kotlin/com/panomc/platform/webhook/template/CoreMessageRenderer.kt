package com.panomc.platform.webhook.template

import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.api.webhook.WebhookDiscordTemplateInfo
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.webhook.WebhookEvents
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.Instant

/**
 * The message of every templated format for the core events, and for every plugin event whose plugin brought no
 * renderer of its own for that format.
 *
 * - With a template (the endpoint's, for this event) the template is filled in with [vars]; a template that gives no
 *   usable message falls back to the built-in one and the delivery log says `TEMPLATE_ERROR`.
 * - Without one the built-in template of the event is used ([defaultTemplate]): a readable message in the site's
 *   language, the same text the panel's editor starts from.
 *
 * [label] reads a text of `webhooks.message.*` in the site's language (`null` = not translated: English is used).
 */
class CoreMessageRenderer(private val label: (key: String) -> String? = { null }) {
    fun render(format: WebhookFormat, envelope: JsonObject, template: String?): RenderedBody {
        val messageFormat = MessageFormat.of(format) ?: return RenderedBody(envelope.encode())
        val full = envelope.getString("event").orEmpty()
        val vars = vars(envelope)
        val custom = template?.takeIf { it.isNotBlank() }

        if (custom != null) {
            TemplateEngine.render(custom, vars, messageFormat)?.let { return RenderedBody(it.encode()) }
        }

        val body = TemplateEngine.render(defaultTemplate(format, full), vars, messageFormat)
            ?: JsonObject().put("event", full)

        return RenderedBody(body.encode(), if (custom != null) TEMPLATE_ERROR else null)
    }

    /** The built-in template of [event] (full name) for [format], as the pretty JSON text the panel shows. */
    fun defaultTemplate(format: WebhookFormat, event: String): String {
        if (format == WebhookFormat.CUSTOM) return CustomFormat.TEMPLATE

        val messageFormat = MessageFormat.of(format) ?: return "{}"

        return messageFormat.template(spec(event)).encodePrettily()
    }

    // ----- the built-in messages -------------------------------------------------------------------------------------

    private fun text(key: String): String = label("webhooks.message.$key")?.takeIf { it.isNotBlank() } ?: ENGLISH[key] ?: key

    /** What the built-in message of [event] says. */
    fun spec(event: String): MessageSpec {
        val open = text("open")

        return when (event) {
            WebhookEvents.USER_REGISTERED -> MessageSpec(
                text("user.registered.title"), text("user.registered.description"), "{url}", GREEN,
                listOf(text("fields.player") to "{data.username}"), open = open
            )

            WebhookEvents.USER_DELETED -> MessageSpec(
                text("user.deleted.title"), text("user.deleted.description"), null, RED,
                listOf(text("fields.player") to "{data.username}"), open = open
            )

            WebhookEvents.TICKET_CREATED -> MessageSpec(
                text("ticket.created.title"), text("ticket.created.description"), "{url}", BLURPLE,
                listOf(text("fields.player") to "{data.username}", text("fields.ticket") to "#{data.id}"), open = open
            )

            WebhookEvents.TICKET_REPLIED -> MessageSpec(
                text("ticket.replied.title"), text("ticket.replied.description"), "{url}", YELLOW,
                listOf(text("fields.ticket") to "#{data.ticketId}", text("fields.replied-by") to "{replier}"), open = open
            )

            WebhookEvents.POST_PUBLISHED -> MessageSpec(
                text("post.published.title"), text("post.published.description"), "{url}", BLURPLE, open = open
            )

            WebhookEvents.TEST_PING -> MessageSpec(text("test.ping.title"), text("test.ping.description"), null, GREEN, open = open)

            else -> MessageSpec("{event}", null, null, GREY, code = "{data}", open = open)
        }
    }

    // ----- the tokens ------------------------------------------------------------------------------------------------

    /**
     * Every token of [envelope]: `event`, `event.name`, `event.iso`, `title` (the built-in title of the event, filled
     * in), `site.name`, `site.url`, `data` (pretty JSON, cut to 1 000 characters), `json.data` / `json.envelope` (the
     * whole JSON value, for a custom body), each `data.<path>` (a millisecond time ending in `At` also as
     * `data.<path>.iso`), and for the core events `url` (the page of the subject) and `replier` (`core.ticket.replied`).
     */
    fun vars(envelope: JsonObject): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        val full = envelope.getString("event").orEmpty()
        val site = envelope.getJsonObject("site") ?: JsonObject()
        val siteUrl = site.getString("url").orEmpty().trimEnd('/')
        val data = envelope.getValue("data") as? JsonObject ?: JsonObject()

        vars["event"] = full
        vars["event.name"] = WebhookEvents.nameOf(full)
        vars["event.iso"] = (envelope.getValue("createdAt") as? Number)?.let { Instant.ofEpochMilli(it.toLong()).toString() }.orEmpty()
        vars["site.name"] = site.getString("name")?.takeIf { it.isNotBlank() } ?: siteUrl.ifEmpty { "Pano" }
        vars["site.url"] = siteUrl
        vars["data"] = TemplateEngine.cut(data.encodePrettily(), DATA_LIMIT)
        vars["json.data"] = data.encode()
        vars["json.envelope"] = envelope.encode()

        flatten("data", data, vars)

        val url = when (full) {
            WebhookEvents.USER_REGISTERED -> data.getString("username")?.let { "$siteUrl/player/$it" }
            WebhookEvents.TICKET_CREATED -> data.getValue("id")?.let { "$siteUrl/panel/tickets/detail/$it" }
            WebhookEvents.TICKET_REPLIED -> data.getValue("ticketId")?.let { "$siteUrl/panel/tickets/detail/$it" }
            WebhookEvents.POST_PUBLISHED -> data.getString("url")
            else -> null
        }

        // a relative link is refused by the chat services: no site URL, no link
        vars["url"] = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()

        if (full == WebhookEvents.TICKET_REPLIED) {
            vars["replier"] = text(if (data.getBoolean("staff") == true) "replier.staff" else "replier.player")
        }

        vars["title"] = fill(spec(full).title, vars)

        return vars
    }

    /** [text] (a plain text with tokens) with its tokens filled in. */
    private fun fill(text: String, vars: Map<String, String>): String = runCatching {
        io.vertx.core.json.Json.decodeValue(TemplateEngine.substitute("\"" + TemplateEngine.escapeJson(text) + "\"", vars)) as String
    }.getOrDefault(text)

    private fun flatten(prefix: String, value: Any?, vars: MutableMap<String, String>, depth: Int = 0) {
        when (value) {
            is JsonObject -> {
                if (prefix != "data") vars[prefix] = value.encode()
                if (depth < MAX_DEPTH) for (key in value.fieldNames()) flatten("$prefix.$key", value.getValue(key), vars, depth + 1)
            }

            is JsonArray -> {
                vars[prefix] = value.encode()
                if (depth < MAX_DEPTH) value.take(MAX_ITEMS).forEachIndexed { index, item -> flatten("$prefix.$index", item, vars, depth + 1) }
            }

            null -> vars[prefix] = ""

            is Number -> {
                vars[prefix] = value.toString()

                if (prefix.endsWith("At") && value.toLong() > 0) vars["$prefix.iso"] = Instant.ofEpochMilli(value.toLong()).toString()
            }

            else -> vars[prefix] = value.toString()
        }
    }

    /** The Discord side as a plugin-style renderer, so a plugin without one gets the core message. */
    val discord: CoreDiscordRenderer = CoreDiscordRenderer(this)

    companion object {
        const val TEMPLATE_ERROR = "TEMPLATE_ERROR"
        const val DATA_LIMIT = 1000

        private const val MAX_DEPTH = 4
        private const val MAX_ITEMS = 20

        const val GREEN = 0x57F287
        const val RED = 0xED4245
        const val YELLOW = 0xFEE75C
        const val BLURPLE = 0x5865F2
        const val GREY = 0x99AAB5

        /** The texts when the site's language has none (and for tests): the en-US locale file says the same. */
        val ENGLISH = mapOf(
            "open" to "Open",
            "fields.player" to "Player",
            "fields.ticket" to "Ticket",
            "fields.replied-by" to "Replied by",
            "replier.staff" to "Staff",
            "replier.player" to "Player",
            "user.registered.title" to "New player registered",
            "user.registered.description" to "**{data.username}** joined {site.name}.",
            "user.deleted.title" to "Player deleted",
            "user.deleted.description" to "The account of **{data.username}** was deleted.",
            "ticket.created.title" to "New ticket: {data.title}",
            "ticket.created.description" to "**{data.username}** opened a new support ticket.",
            "ticket.replied.title" to "New reply on ticket #{data.ticketId}",
            "ticket.replied.description" to "A support ticket got a new reply.",
            "post.published.title" to "{data.title}",
            "post.published.description" to "A new post was published on {site.name}.",
            "test.ping.title" to "Test message",
            "test.ping.description" to "This webhook works. Messages from {site.name} will look like this."
        )
    }
}

/** [CoreMessageRenderer]'s Discord message behind the plugin renderer interface. */
class CoreDiscordRenderer(private val core: CoreMessageRenderer) : WebhookDiscordRenderer, WebhookDiscordTemplateInfo {
    override suspend fun render(event: String, envelope: JsonObject, template: String?): RenderedBody =
        core.render(WebhookFormat.DISCORD, envelope, template)

    override fun defaultTemplate(event: String): String = core.defaultTemplate(WebhookFormat.DISCORD, event)

    override fun tokens(event: String, envelope: JsonObject): Map<String, String> = core.vars(envelope)
}
