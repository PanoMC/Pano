package com.panomc.platform.webhook.template

import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.webhook.WebhookEvents
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageTemplateTest {
    private fun envelope(event: String, data: JsonObject) = JsonObject()
        .put("id", "e1").put("event", event).put("source", WebhookEvents.sourceOf(event)).put("createdAt", 1_700_000_000_000L)
        .put("apiVersion", 1).put("site", JsonObject().put("name", "CatSMP").put("url", "https://cat.example/")).put("data", data)

    private val ticket = envelope(
        WebhookEvents.TICKET_CREATED,
        JsonObject().put("id", 7).put("title", "Help <!channel> \"me\"").put("categoryId", 1).put("userId", 12).put("username", "Steve")
    )

    private val core = CoreMessageRenderer()

    private fun body(format: WebhookFormat, template: String? = null) = JsonObject(core.render(format, ticket, template).body)

    // ----- engine ----------------------------------------------------------------------------------------------------

    @Test
    fun `tokens are escaped inside strings and only whole JSON values stand outside them`() {
        val vars = mapOf("data.title" to "a\"b", "data.id" to "5", "json.data" to """{"x":1}""")
        val out = TemplateEngine.substitute("""{"content":"{data.title}","color":{data.title},"n":{data.id},"d":{json.data}}""", vars)

        assertEquals("""{"content":"a\"b","color":0,"n":5,"d":{"x":1}}""", out)
    }

    @Test
    fun `an unknown name stays as it is and a fallback fills an empty value`() {
        val out = TemplateEngine.substitute("""{"content":"{nope} {data.missing|none} {site.name}"}""", mapOf("site.name" to "S"))

        assertEquals("""{"content":"{nope} none S"}""", out)
    }

    @Test
    fun `validation names broken and empty templates per format`() {
        assertEquals("INVALID_JSON", TemplateEngine.validate("""{"content": "x""", DiscordFormat))
        assertEquals("EMPTY", TemplateEngine.validate("""{"username":"bot"}""", DiscordFormat))
        assertEquals("EMPTY", TemplateEngine.validate("""{"parse_mode":"HTML"}""", TelegramFormat))
        assertNull(TemplateEngine.validate("""{"embeds":[{"title":"{data.title}","color":{event.color}}]}""", DiscordFormat))
        assertNull(TemplateEngine.validate("""{"data":{json.data}}""", CustomFormat))
    }

    @Test
    fun `every built-in template validates in every format`() {
        val events = listOf(
            WebhookEvents.USER_REGISTERED, WebhookEvents.USER_DELETED, WebhookEvents.TICKET_CREATED,
            WebhookEvents.TICKET_REPLIED, WebhookEvents.POST_PUBLISHED, WebhookEvents.TEST_PING, "market.order.paid"
        )

        for (format in MessageFormat.templated) for (event in events) {
            assertNull(TemplateEngine.validate(core.defaultTemplate(format, event), MessageFormat.of(format)!!), "$format $event")
        }
    }

    // ----- formats ---------------------------------------------------------------------------------------------------

    @Test
    fun `discord gets a readable embed in the site language with mentions off`() {
        val localized = CoreMessageRenderer { key -> if (key == "webhooks.message.ticket.created.title") "Yeni talep: {data.title}" else null }
        val discord = JsonObject(localized.render(WebhookFormat.DISCORD, ticket, null).body)
        val embed = discord.getJsonArray("embeds").getJsonObject(0)

        assertEquals("Yeni talep: Help <!channel> \"me\"", embed.getString("title"))
        assertEquals("https://cat.example/panel/tickets/detail/7", embed.getString("url"))
        assertEquals("CatSMP", embed.getJsonObject("footer").getString("text"))
        assertEquals("2023-11-14T22:13:20Z", embed.getString("timestamp"))
        assertTrue(discord.getJsonObject("allowed_mentions").getJsonArray("parse").isEmpty)
    }

    @Test
    fun `slack escapes player text so it can never mention a channel`() {
        val slack = body(WebhookFormat.SLACK)

        assertEquals("New ticket: Help &lt;!channel&gt; \"me\"", slack.getString("text"))
        assertTrue(slack.getJsonArray("blocks").encode().contains("https://cat.example/panel/tickets/detail/7"))
        assertFalse(slack.encode().contains("<!channel>"))
    }

    @Test
    fun `telegram is HTML with escaped values and takes the chat from the URL`() {
        val telegram = body(WebhookFormat.TELEGRAM)

        assertTrue(telegram.getString("text").startsWith("<b>New ticket: Help &lt;!channel&gt; \"me\"</b>"))
        assertEquals("HTML", telegram.getString("parse_mode"))

        val sent = JsonObject(TelegramFormat.withChat(telegram.encode(), "https://api.telegram.org/bot1:a/sendMessage?chat_id=-100%2042"))

        assertEquals("-100 42", sent.getString("chat_id"))
    }

    @Test
    fun `teams gets an adaptive card and loses a link button without a link`() {
        val deleted = envelope(WebhookEvents.USER_DELETED, JsonObject().put("id", 1).put("username", "Alex"))
        val card = JsonObject(core.render(WebhookFormat.TEAMS, deleted, null).body)
            .getJsonArray("attachments").getJsonObject(0).getJsonObject("content")

        assertEquals("AdaptiveCard", card.getString("type"))
        assertFalse(card.containsKey("actions"))
        assertEquals("Attention", card.getJsonArray("body").getJsonObject(0).getString("color"))
    }

    @Test
    fun `a custom body is the event in a flat shape`() {
        val custom = body(WebhookFormat.CUSTOM)

        assertEquals("core.ticket.created", custom.getString("event"))
        assertEquals("New ticket: Help <!channel> \"me\"", custom.getString("title"))
        assertEquals("Steve", custom.getJsonObject("data").getString("username"))
    }

    @Test
    fun `a custom template is used, a broken one falls back with a warning`() {
        val custom = core.render(WebhookFormat.DISCORD, ticket, """{"content":"{data.username}: {data.title}"}""")

        assertEquals("Steve: Help <!channel> \"me\"", JsonObject(custom.body).getString("content"))
        assertNull(custom.warning)

        val broken = core.render(WebhookFormat.SLACK, ticket, """{"text":""")

        assertEquals(CoreMessageRenderer.TEMPLATE_ERROR, broken.warning)
        assertTrue(JsonObject(broken.body).containsKey("blocks"))
    }

    @Test
    fun `discord limits are applied`() {
        val body = TemplateEngine.render(
            """{"content":"@everyone {data.t}","embeds":[{"title":"x","fields":[{"name":"a","value":""}]}]}""",
            mapOf("data.t" to "y".repeat(3000)), DiscordFormat
        )!!

        assertEquals(DiscordFormat.CONTENT_MAX, body.getString("content").length)
        assertFalse(body.getJsonArray("embeds").getJsonObject(0).containsKey("fields"))
    }

    @Test
    fun `the most specific template of an event wins`() {
        val templates = mapOf("*" to "all", "core.*" to "core", "core.ticket.created" to "ticket")

        assertEquals("ticket", WebhookTemplates.resolve(templates, "core.ticket.created"))
        assertEquals("core", WebhookTemplates.resolve(templates, "core.user.registered"))
        assertEquals("all", WebhookTemplates.resolve(templates, "market.order.paid"))
        assertNull(WebhookTemplates.resolve(emptyMap(), "core.user.registered"))
        assertTrue(WebhookTemplates.isValidKey("market.*"))
        assertFalse(WebhookTemplates.isValidKey("Bad Key"))
    }
}
