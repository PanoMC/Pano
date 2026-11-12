package com.panomc.platform.webhook.template

import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.webhook.template.TemplateEngine.cut
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * What one built-in message says, whatever service it goes to. Texts may hold tokens; [description] uses `**bold**`
 * (each format turns it into its own markup). [code] is shown as a code block (the data of an event nobody wrote a
 * message for).
 */
class MessageSpec(
    val title: String,
    val description: String? = null,
    val url: String? = null,
    val color: Int = 0x5865F2,
    val fields: List<Pair<String, String>> = emptyList(),
    val code: String? = null,
    /** The label of the link button. */
    val open: String = "Open"
)

/**
 * One chat service a templated webhook endpoint can talk to: how a value is escaped inside its texts, how a filled-in
 * template becomes a body it accepts ([finish]: limits applied, empty parts dropped, `null` = no message at all), and
 * what the built-in message of an event looks like ([template]).
 */
interface MessageFormat {
    val format: WebhookFormat

    fun escapeValue(value: String): String = value

    fun finish(body: JsonObject): JsonObject?

    fun template(spec: MessageSpec): JsonObject

    companion object {
        private val ALL = listOf(DiscordFormat, SlackFormat, TelegramFormat, TeamsFormat, CustomFormat).associateBy { it.format }

        /** The format of a templated endpoint, `null` for `JSON` (the plain envelope). */
        fun of(format: WebhookFormat): MessageFormat? = ALL[format]

        /** Every format that takes templates. */
        val templated: Set<WebhookFormat> = ALL.keys

        /** The formats that never sign (the service would not check a signature). */
        val unsigned: Set<WebhookFormat> = setOf(WebhookFormat.DISCORD, WebhookFormat.SLACK, WebhookFormat.TELEGRAM, WebhookFormat.TEAMS)
    }
}

private val BOLD = Regex("""\*\*(.+?)\*\*""")

private fun String?.blank() = this == null || this.isBlank()

private fun JsonObject.cutKey(key: String, max: Int) {
    (getValue(key) as? String)?.let { put(key, cut(it, max)) }
}

private fun JsonObject.dropBlank(vararg keys: String) {
    for (key in keys) if ((getValue(key) as? String)?.isBlank() == true) remove(key)
}

// ---------------------------------------------------------------------------------------------------------------------
// Discord
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Discord webhooks: `content`, `username`, `avatar_url`, up to 10 `embeds`. [finish] enforces Discord's limits and
 * **forces** `allowed_mentions: {"parse": []}`, so text from a player can never ping `@everyone`.
 */
object DiscordFormat : MessageFormat {
    override val format = WebhookFormat.DISCORD

    const val CONTENT_MAX = 2000
    const val USERNAME_MAX = 80
    const val TITLE_MAX = 256
    const val DESCRIPTION_MAX = 4096
    const val FIELD_NAME_MAX = 256
    const val FIELD_VALUE_MAX = 1024
    const val FOOTER_MAX = 2048
    const val AUTHOR_MAX = 256
    const val MAX_FIELDS = 25
    const val MAX_EMBEDS = 10
    const val EMBED_TOTAL_MAX = 6000

    override fun template(spec: MessageSpec): JsonObject {
        val embed = JsonObject().put("title", spec.title)

        val description = listOfNotNull(spec.description, spec.code?.let { "```json\n$it\n```" }).joinToString("\n")

        if (description.isNotEmpty()) embed.put("description", description)
        if (spec.url != null) embed.put("url", spec.url)

        embed.put("color", spec.color)

        if (spec.fields.isNotEmpty()) {
            embed.put("fields", JsonArray(spec.fields.map { (name, value) -> JsonObject().put("name", name).put("value", value).put("inline", true) }))
        }

        embed.put("footer", JsonObject().put("text", "{site.name}"))
        embed.put("timestamp", "{event.iso}")

        return JsonObject().put("embeds", JsonArray().add(embed))
    }

    override fun finish(body: JsonObject): JsonObject? {
        (body.getValue("content") as? String)?.let { if (it.isEmpty()) body.remove("content") else body.put("content", cut(it, CONTENT_MAX)) }
        (body.getValue("username") as? String)?.let { if (it.isBlank()) body.remove("username") else body.put("username", cut(it.trim(), USERNAME_MAX)) }
        body.dropBlank("avatar_url")

        val embeds = body.getValue("embeds") as? JsonArray

        if (embeds != null) {
            val kept = JsonArray()

            for (value in embeds.take(MAX_EMBEDS)) (value as? JsonObject)?.let { cleanEmbed(it) }?.let { kept.add(it) }

            if (kept.isEmpty) body.remove("embeds") else body.put("embeds", kept)
        } else {
            body.remove("embeds")
        }

        // forced, whatever the template says: text from a player can never ping anybody
        body.put("allowed_mentions", JsonObject().put("parse", JsonArray()))

        val hasContent = !(body.getValue("content") as? String).blank() || ((body.getValue("embeds") as? JsonArray)?.size() ?: 0) > 0

        return body.takeIf { hasContent }
    }

    /** The embed with its limits applied, or `null` when nothing visible is left in it. */
    private fun cleanEmbed(embed: JsonObject): JsonObject? {
        embed.dropBlank("url", "timestamp")
        embed.cutKey("title", TITLE_MAX)
        embed.cutKey("description", DESCRIPTION_MAX)
        embed.dropBlank("title", "description")

        (embed.getValue("color") as? Number)?.let { embed.put("color", it.toInt().coerceIn(0, 0xFFFFFF)) }

        cleanNamed(embed, "footer", "text", FOOTER_MAX, "icon_url")
        cleanNamed(embed, "author", "name", AUTHOR_MAX, "icon_url", "url")

        for (key in listOf("thumbnail", "image")) {
            val obj = embed.getValue(key) as? JsonObject

            if (obj != null && (obj.getValue("url") as? String).blank()) embed.remove(key)
        }

        val fields = JsonArray()

        for (value in (embed.getValue("fields") as? JsonArray) ?: JsonArray()) {
            val field = value as? JsonObject ?: continue
            val name = (field.getValue("name") as? String).orEmpty()
            val text = (field.getValue("value") as? String).orEmpty()

            if (text.isBlank()) continue

            // Discord refuses a field without a name; a zero-width space keeps the value
            field.put("name", cut(name.ifBlank { "​" }, FIELD_NAME_MAX))
            field.put("value", cut(text, FIELD_VALUE_MAX))
            fields.add(field)
        }

        while (fields.size() > MAX_FIELDS) fields.remove(fields.size() - 1)

        if (fields.isEmpty) embed.remove("fields") else embed.put("fields", fields)

        // 6000 characters over title, description, field names and values, footer text and author name: fields go from the end
        while (embedSize(embed) > EMBED_TOTAL_MAX && fields.size() > 0) {
            fields.remove(fields.size() - 1)

            if (fields.isEmpty) embed.remove("fields")
        }

        val visible = listOf("title", "description", "fields", "footer", "author", "image", "thumbnail").any { embed.containsKey(it) }

        return embed.takeIf { visible }
    }

    private fun cleanNamed(embed: JsonObject, key: String, textKey: String, max: Int, vararg urlKeys: String) {
        val obj = embed.getValue(key) as? JsonObject ?: return

        (obj.getValue(textKey) as? String)?.let { if (it.isBlank()) obj.remove(textKey) else obj.put(textKey, cut(it, max)) }
        obj.dropBlank(*urlKeys)

        if (!obj.containsKey(textKey)) embed.remove(key)
    }

    private fun embedSize(embed: JsonObject): Int {
        var n = (embed.getValue("title") as? String)?.length ?: 0

        n += (embed.getValue("description") as? String)?.length ?: 0
        n += ((embed.getValue("footer") as? JsonObject)?.getValue("text") as? String)?.length ?: 0
        n += ((embed.getValue("author") as? JsonObject)?.getValue("name") as? String)?.length ?: 0

        for (value in (embed.getValue("fields") as? JsonArray) ?: JsonArray()) {
            val field = value as? JsonObject ?: continue

            n += (field.getValue("name") as? String)?.length ?: 0
            n += (field.getValue("value") as? String)?.length ?: 0
        }

        return n
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Slack (and the services that take Slack's format: Mattermost, Rocket.Chat)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Slack incoming webhooks: `text` (the notification text, required) and up to 50 `blocks`, plus `username` /
 * `icon_url` where the workspace allows them. Values are HTML-escaped, so `<!channel>` or `<@U123>` typed by a player
 * stays text.
 */
object SlackFormat : MessageFormat {
    override val format = WebhookFormat.SLACK

    const val TEXT_MAX = 3000
    const val HEADER_MAX = 150
    const val FIELD_MAX = 2000
    const val MAX_FIELDS = 10
    const val MAX_BLOCKS = 50
    const val BUTTON_MAX = 75

    override fun escapeValue(value: String) = TemplateEngine.escapeHtml(value)

    private fun mrkdwn(text: String) = JsonObject().put("type", "mrkdwn").put("text", text)

    private fun plain(text: String) = JsonObject().put("type", "plain_text").put("text", text).put("emoji", true)

    override fun template(spec: MessageSpec): JsonObject {
        val blocks = JsonArray()

        blocks.add(JsonObject().put("type", "header").put("text", plain(spec.title)))

        val description = listOfNotNull(spec.description?.replace(BOLD, "*$1*"), spec.code?.let { "```$it```" }).joinToString("\n")

        if (description.isNotEmpty()) blocks.add(JsonObject().put("type", "section").put("text", mrkdwn(description)))

        if (spec.fields.isNotEmpty()) {
            blocks.add(
                JsonObject().put("type", "section")
                    .put("fields", JsonArray(spec.fields.map { (name, value) -> mrkdwn("*$name*\n$value") }))
            )
        }

        if (spec.url != null) {
            blocks.add(
                JsonObject().put("type", "actions").put(
                    "elements",
                    JsonArray().add(JsonObject().put("type", "button").put("text", plain(spec.open)).put("url", spec.url))
                )
            )
        }

        blocks.add(JsonObject().put("type", "context").put("elements", JsonArray().add(mrkdwn("{site.name}"))))

        return JsonObject().put("text", spec.title).put("blocks", blocks)
    }

    override fun finish(body: JsonObject): JsonObject? {
        body.dropBlank("username", "icon_url", "icon_emoji")

        val blocks = JsonArray()

        for (value in ((body.getValue("blocks") as? JsonArray) ?: JsonArray()).take(MAX_BLOCKS)) {
            (value as? JsonObject)?.let { cleanBlock(it) }?.let { blocks.add(it) }
        }

        if (blocks.isEmpty) body.remove("blocks") else body.put("blocks", blocks)

        // the notification text: the template's, else the first text of a block
        val text = (body.getValue("text") as? String)?.takeIf { it.isNotBlank() } ?: firstText(blocks)

        if (text.isNullOrBlank()) return null

        body.put("text", cut(text, TEXT_MAX))

        return body
    }

    private fun firstText(blocks: JsonArray): String? =
        blocks.firstNotNullOfOrNull { (((it as JsonObject).getValue("text") as? JsonObject)?.getValue("text") as? String)?.takeIf { text -> text.isNotBlank() } }

    private fun cleanText(obj: JsonObject?, max: Int): Boolean {
        val text = obj?.getValue("text") as? String ?: return false

        if (text.isBlank()) return false

        obj.put("text", cut(text, max))

        return true
    }

    private fun cleanBlock(block: JsonObject): JsonObject? = when (block.getValue("type")) {
        "header" -> block.takeIf { cleanText(block.getValue("text") as? JsonObject, HEADER_MAX) }

        "section" -> {
            val hasText = cleanText(block.getValue("text") as? JsonObject, TEXT_MAX)

            if (!hasText) block.remove("text")

            val fields = JsonArray()

            for (value in (block.getValue("fields") as? JsonArray) ?: JsonArray()) {
                val field = value as? JsonObject ?: continue
                val text = (field.getValue("text") as? String).orEmpty()

                // "*Player*\n" with an empty value says nothing
                if (text.substringAfter('\n', text).isBlank()) continue

                field.put("text", cut(text, FIELD_MAX))
                fields.add(field)
            }

            while (fields.size() > MAX_FIELDS) fields.remove(fields.size() - 1)

            if (fields.isEmpty) block.remove("fields") else block.put("fields", fields)

            (block.getValue("accessory") as? JsonObject)?.let { accessory ->
                if (accessory.getValue("type") == "image" && (accessory.getValue("image_url") as? String).blank()) block.remove("accessory")
            }

            block.takeIf { hasText || !fields.isEmpty }
        }

        "actions" -> {
            val elements = JsonArray()

            for (value in (block.getValue("elements") as? JsonArray) ?: JsonArray()) {
                val element = value as? JsonObject ?: continue

                // a link button without a link is refused by Slack
                if (element.getValue("type") == "button" && element.containsKey("url") && (element.getValue("url") as? String).blank()) continue
                if (!cleanText(element.getValue("text") as? JsonObject, BUTTON_MAX)) continue

                elements.add(element)
            }

            if (elements.isEmpty) null else block.put("elements", elements)
        }

        "context" -> {
            val elements = JsonArray()

            for (value in (block.getValue("elements") as? JsonArray) ?: JsonArray()) {
                val element = value as? JsonObject ?: continue

                if (element.getValue("type") == "image") {
                    if (!(element.getValue("image_url") as? String).blank()) elements.add(element)
                } else if (cleanText(element, TEXT_MAX)) {
                    elements.add(element)
                }
            }

            if (elements.isEmpty) null else block.put("elements", elements)
        }

        "image" -> block.takeIf { !(block.getValue("image_url") as? String).blank() }

        "divider" -> block

        // a block type the editor does not know is passed on as it is
        else -> block
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Telegram
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Telegram's `sendMessage` with `parse_mode: HTML`: `text` (required, 4 096 characters), optional link buttons in
 * `reply_markup.inline_keyboard`. Values are HTML-escaped, so player text can never become markup. `chat_id` comes
 * from the endpoint URL (`?chat_id=`) and is added when the message is sent.
 */
object TelegramFormat : MessageFormat {
    override val format = WebhookFormat.TELEGRAM

    const val TEXT_MAX = 4096
    const val BUTTON_MAX = 64

    override fun escapeValue(value: String) = TemplateEngine.escapeHtml(value)

    override fun template(spec: MessageSpec): JsonObject {
        val lines = ArrayList<String>()

        lines += "<b>${spec.title}</b>"

        spec.description?.let { lines += ""; lines += it.replace(BOLD, "<b>$1</b>") }
        spec.code?.let { lines += ""; lines += "<pre>$it</pre>" }

        if (spec.fields.isNotEmpty()) {
            lines += ""
            spec.fields.forEach { (name, value) -> lines += "<b>$name:</b> $value" }
        }

        lines += ""
        lines += "<i>{site.name}</i>"

        val body = JsonObject()
            .put("text", lines.joinToString("\n"))
            .put("parse_mode", "HTML")
            .put("link_preview_options", JsonObject().put("is_disabled", true))

        if (spec.url != null) {
            body.put(
                "reply_markup",
                JsonObject().put("inline_keyboard", JsonArray().add(JsonArray().add(JsonObject().put("text", spec.open).put("url", spec.url))))
            )
        }

        return body
    }

    override fun finish(body: JsonObject): JsonObject? {
        val text = body.getValue("text") as? String

        if (text.isNullOrBlank()) return null

        body.put("text", cut(text, TEXT_MAX))

        (body.getValue("reply_markup") as? JsonObject)?.let { markup ->
            val rows = JsonArray()

            for (row in (markup.getValue("inline_keyboard") as? JsonArray) ?: JsonArray()) {
                val buttons = JsonArray()

                for (value in (row as? JsonArray) ?: JsonArray()) {
                    val button = value as? JsonObject ?: continue

                    if ((button.getValue("text") as? String).blank()) continue
                    if (button.containsKey("url") && (button.getValue("url") as? String).blank()) continue

                    button.cutKey("text", BUTTON_MAX)
                    buttons.add(button)
                }

                if (!buttons.isEmpty) rows.add(buttons)
            }

            if (rows.isEmpty) body.remove("reply_markup") else markup.put("inline_keyboard", rows)
        }

        return body
    }

    /** [body] with the `chat_id` of the endpoint [url] (`?chat_id=`), unless the template set one. */
    fun withChat(body: String, url: String): String {
        val chat = chatOf(url) ?: return body

        return runCatching {
            val obj = JsonObject(body)

            if (obj.getValue("chat_id") == null) obj.put("chat_id", chat)

            obj.encode()
        }.getOrDefault(body)
    }

    /** The `chat_id` query parameter of [url], or `null`. */
    fun chatOf(url: String): String? = runCatching {
        java.net.URI(url).rawQuery?.split('&')
            ?.map { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
            ?.firstOrNull { it.first == "chat_id" }?.second?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

// ---------------------------------------------------------------------------------------------------------------------
// Microsoft Teams (Workflows webhooks)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * A Teams Workflows webhook ("Post to a channel when a webhook request is received"): a `message` with one Adaptive
 * Card. [finish] drops empty text blocks, facts without a value and link buttons without a link.
 */
object TeamsFormat : MessageFormat {
    override val format = WebhookFormat.TEAMS

    const val CARD_TYPE = "application/vnd.microsoft.card.adaptive"
    const val TEXT_MAX = 4000

    /** The Adaptive Card text colours closest to an event colour. */
    fun colorName(color: Int): String = when (color) {
        0x57F287 -> "Good"
        0xED4245 -> "Attention"
        0xFEE75C -> "Warning"
        0x99AAB5 -> "Default"
        else -> "Accent"
    }

    override fun template(spec: MessageSpec): JsonObject {
        val body = JsonArray()

        body.add(
            JsonObject().put("type", "TextBlock").put("text", spec.title).put("size", "Large").put("weight", "Bolder")
                .put("color", colorName(spec.color)).put("wrap", true)
        )

        spec.description?.let { body.add(JsonObject().put("type", "TextBlock").put("text", it).put("wrap", true)) }
        spec.code?.let { body.add(JsonObject().put("type", "TextBlock").put("text", it).put("fontType", "Monospace").put("wrap", true)) }

        if (spec.fields.isNotEmpty()) {
            body.add(JsonObject().put("type", "FactSet").put("facts", JsonArray(spec.fields.map { (name, value) -> JsonObject().put("title", name).put("value", value) })))
        }

        body.add(JsonObject().put("type", "TextBlock").put("text", "{site.name}").put("isSubtle", true).put("size", "Small").put("wrap", true))

        val card = JsonObject()
            .put("\$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
            .put("type", "AdaptiveCard")
            .put("version", "1.4")
            .put("body", body)

        if (spec.url != null) {
            card.put("actions", JsonArray().add(JsonObject().put("type", "Action.OpenUrl").put("title", spec.open).put("url", spec.url)))
        }

        return JsonObject().put("type", "message")
            .put("attachments", JsonArray().add(JsonObject().put("contentType", CARD_TYPE).put("content", card)))
    }

    override fun finish(body: JsonObject): JsonObject? {
        val attachments = body.getValue("attachments") as? JsonArray ?: return null
        val kept = JsonArray()

        for (value in attachments) {
            val attachment = value as? JsonObject ?: continue
            val card = attachment.getValue("content") as? JsonObject ?: continue

            card.put("body", cleanElements((card.getValue("body") as? JsonArray) ?: JsonArray()))

            (card.getValue("actions") as? JsonArray)?.let { actions ->
                val usable = JsonArray(actions.filterIsInstance<JsonObject>().filter { action ->
                    !(action.getValue("title") as? String).blank() &&
                            !(action.getValue("type") == "Action.OpenUrl" && (action.getValue("url") as? String).blank())
                })

                if (usable.isEmpty) card.remove("actions") else card.put("actions", usable)
            }

            if (!(card.getValue("body") as JsonArray).isEmpty || card.containsKey("actions")) kept.add(attachment)
        }

        if (kept.isEmpty) return null

        return body.put("attachments", kept)
    }

    private fun cleanElements(elements: JsonArray): JsonArray {
        val out = JsonArray()

        for (value in elements) {
            val element = value as? JsonObject ?: continue

            when (element.getValue("type")) {
                "TextBlock" -> {
                    val text = element.getValue("text") as? String

                    if (text.blank()) continue

                    element.put("text", cut(text!!, TEXT_MAX))
                }

                "FactSet" -> {
                    val facts = JsonArray(((element.getValue("facts") as? JsonArray) ?: JsonArray()).filterIsInstance<JsonObject>()
                        .filter { !(it.getValue("value") as? String).blank() })

                    if (facts.isEmpty) continue

                    element.put("facts", facts)
                }

                "Image" -> if ((element.getValue("url") as? String).blank()) continue

                "Container", "Column" -> {
                    val items = cleanElements((element.getValue("items") as? JsonArray) ?: JsonArray())

                    if (items.isEmpty) continue

                    element.put("items", items)
                }
            }

            out.add(element)
        }

        return out
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Custom JSON
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Any JSON receiver (n8n, Zapier, Make, Home Assistant, ntfy, an own service): the body is exactly what the template
 * says. Values are not escaped beyond JSON; the built-in template is the event in a small, flat shape.
 */
object CustomFormat : MessageFormat {
    override val format = WebhookFormat.CUSTOM

    override fun template(spec: MessageSpec): JsonObject = JsonObject()

    /** The built-in custom body: the same for every event. */
    const val TEMPLATE = """{
  "event": "{event}",
  "title": "{title}",
  "url": "{url}",
  "site": {
    "name": "{site.name}",
    "url": "{site.url}"
  },
  "createdAt": "{event.iso}",
  "data": {json.data}
}"""

    override fun finish(body: JsonObject): JsonObject? = body.takeIf { !it.isEmpty }
}
