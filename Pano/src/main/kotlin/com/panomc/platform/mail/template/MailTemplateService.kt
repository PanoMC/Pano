package com.panomc.platform.mail.template

import com.github.jknack.handlebars.EscapingStrategy
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.panomc.platform.AppConstants
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailTemplate
import com.panomc.platform.mail.MailOptions
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.Year

/**
 * Resolves, renders and validates mail templates (bundled defaults of core and plugins, overridden per language
 * from the panel).
 *
 * Resolution for `(owner, key, variant, locale)`: DB override of that locale → bundled file of that locale →
 * bundled file of the same language (`pt-BR` → `pt`) → DB override of en-US → bundled en-US.
 *
 * Rendering in [MailTemplateContent.Mode.FIELDS]: every field is rendered with the system and sender variables,
 * then the shared layout with the results (`content` = the rendered body). Plain-text fields (subject, preheader,
 * button label and URL) are rendered unescaped and escaped once by the layout; HTML fields (body, footer) are
 * rendered with HTML escaping and inserted raw. [MailTemplateContent.Mode.HTML] renders the body alone.
 */
@Service
class MailTemplateService internal constructor(
    private val registry: MailTemplateRegistry,
    private val overrides: suspend (sqlClient: SqlClient?, owner: String, key: String, variant: String, locale: String) -> MailTemplate?,
    private val defaultLayoutProvider: () -> String
) {
    @Autowired
    constructor(registry: MailTemplateRegistry, @Lazy databaseManager: DatabaseManager) : this(
        registry,
        { sqlClient, owner, key, variant, locale ->
            sqlClient?.let { databaseManager.mailTemplateDao.get(owner, key, variant, locale, it) }
        },
        { readLayoutResource() }
    )

    private val htmlHandlebars = Handlebars()
    private val textHandlebars = Handlebars().with(EscapingStrategy.NOOP)

    private val cache = object : LinkedHashMap<String, Pair<String, Template>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Template>>?) = size > MAX_CACHE
    }

    private val defaultLayout: String by lazy { defaultLayoutProvider() }

    /** One resolved template and where it came from. */
    data class Resolved(
        val content: MailTemplateContent,
        /** The locale the template actually came from. */
        val locale: String,
        /** From a panel override (DB) rather than a bundled file. */
        val customized: Boolean,
        internal val cacheId: String
    )

    data class Layout(val body: String, val customized: Boolean, internal val cacheId: String)

    data class RenderedMail(val subject: String, val html: String, val text: String)

    /** Field name to error code (`REQUIRED`, `TOO_LONG`, `TEMPLATE_ERROR`, …) plus the first error's message. */
    data class ValidationResult(val fields: Map<String, String>, val message: String?) {
        val valid get() = fields.isEmpty()
    }

    /** No template exists in any fallback step, or the mail / variant is not registered. */
    class TemplateNotFoundException(message: String) : IllegalStateException(message)

    /**
     * The template [locale] gets. With [skipOwnOverride] the DB override of [locale] itself is left out: the
     * "default" the panel shows next to an override.
     */
    suspend fun resolve(
        owner: String,
        key: String,
        variant: String,
        locale: String,
        sqlClient: SqlClient?,
        skipOwnOverride: Boolean = false
    ): Resolved? {
        val fallback = AppConstants.DEFAULT_LOCALE_CODE
        val language = locale.substringBefore('-').takeIf { it != locale && it.isNotBlank() }

        if (!skipOwnOverride) {
            dbStep(owner, key, variant, locale, sqlClient)?.let { return it }
        }

        bundledStep(owner, key, variant, locale)?.let { return it }

        language?.let { bundledStep(owner, key, variant, it) }?.let { return it }

        // For en-US itself these two are the first two steps again.
        if (locale != fallback) {
            dbStep(owner, key, variant, fallback, sqlClient)?.let { return it }
            bundledStep(owner, key, variant, fallback)?.let { return it }
        }

        return null
    }

    private suspend fun dbStep(owner: String, key: String, variant: String, locale: String, sqlClient: SqlClient?): Resolved? {
        val row = overrides(sqlClient, owner, key, variant, locale) ?: return null

        return Resolved(row.toContent(), locale, true, "db:$owner/$key/$variant/$locale@${row.updatedAt}")
    }

    private fun bundledStep(owner: String, key: String, variant: String, locale: String): Resolved? {
        val source = registry.readBundled(owner, key, variant, locale) ?: return null

        return Resolved(MailTemplateContent.parse(source), locale, false, "res:$owner/$key/$variant/$locale")
    }

    /** The layout override, else the bundled `mail/layout.hbs`. */
    suspend fun layout(sqlClient: SqlClient?): Layout {
        val row = overrides(
            sqlClient,
            MailTemplate.LAYOUT_OWNER,
            MailTemplate.LAYOUT_KEY,
            MailTemplate.LAYOUT_VARIANT,
            MailTemplate.LAYOUT_LOCALE
        )

        return if (row != null) Layout(row.body, true, "db:layout@${row.updatedAt}")
        else Layout(defaultLayout, false, "res:layout")
    }

    fun defaultLayout(): String = defaultLayout

    /**
     * Renders the mail a sender asked for.
     *
     * @throws TemplateNotFoundException when the mail / variant is not registered or no template resolves
     */
    suspend fun renderForSend(
        owner: String,
        key: String,
        variant: String,
        locale: String,
        variables: Map<String, Any?>,
        system: Map<String, Any?>,
        sqlClient: SqlClient?
    ): RenderedMail {
        registry.variant(owner, key, variant)
            ?: throw TemplateNotFoundException("Mail template $owner/$key/$variant is not registered")

        val resolved = resolve(owner, key, variant, locale, sqlClient)
            ?: throw TemplateNotFoundException("No template for mail $owner/$key/$variant in $locale or en-US")

        val layout = if (resolved.content.mode == MailTemplateContent.Mode.FIELDS) layout(sqlClient) else null

        return render(resolved.content, layout, variables, system, resolved.cacheId)
    }

    /**
     * Renders [content] with [system] + [variables]. [layout] is required in fields mode. [cacheId] (null for
     * unsaved content) keys the compiled templates.
     */
    fun render(
        content: MailTemplateContent,
        layout: Layout?,
        variables: Map<String, Any?>,
        system: Map<String, Any?>,
        cacheId: String? = null
    ): RenderedMail {
        val context = HashMap<String, Any?>(system).apply { putAll(variables) }

        fun field(name: String, source: String, html: Boolean): String {
            if (source.isBlank()) return ""

            return compile(cacheId?.let { "$it|$name" }, source, html).apply(context).trim()
        }

        val websiteName = system[SYSTEM_WEBSITE_NAME]?.toString().orEmpty()
        val subject = MailOptions.sanitizeSubject(field(FIELD_SUBJECT, content.subject, html = false)) ?: websiteName

        if (content.mode == MailTemplateContent.Mode.HTML) {
            val html = field(MailTemplateContent.FIELD_BODY, content.body, html = true)

            return RenderedMail(subject, html, toPlainText(html))
        }

        requireNotNull(layout) { "A layout is required in fields mode" }

        val preheader = field(MailTemplateContent.FIELD_PREHEADER, content.preheader, html = false)
        val body = field(MailTemplateContent.FIELD_BODY, content.body, html = true)
        val buttonLabel = field(MailTemplateContent.FIELD_BUTTON_LABEL, content.buttonLabel, html = false)
        val buttonUrl = field(MailTemplateContent.FIELD_BUTTON_URL, content.buttonUrl, html = false)
        val footer = field(MailTemplateContent.FIELD_FOOTER, content.footer, html = true)

        val layoutContext = HashMap<String, Any?>(context).apply {
            put(FIELD_SUBJECT, subject)
            put(MailTemplateContent.FIELD_PREHEADER, preheader)
            put(LAYOUT_CONTENT, body)
            put(MailTemplateContent.FIELD_BUTTON_LABEL, buttonLabel.ifBlank { buttonUrl })
            put(MailTemplateContent.FIELD_BUTTON_URL, buttonUrl)
            put(MailTemplateContent.FIELD_FOOTER, footer)
        }

        val html = compile(layout.cacheId, layout.body, html = true).apply(layoutContext)

        return RenderedMail(subject, html, plainText(body, buttonLabel, buttonUrl, footer))
    }

    /**
     * Checks [content] the way the panel's save does: a subject that is not blank and at most 255 characters
     * after sanitising, and every field compiling and rendering with [sampleVariables].
     */
    fun validate(content: MailTemplateContent, sampleVariables: Map<String, Any?>, system: Map<String, Any?>): ValidationResult {
        val fields = linkedMapOf<String, String>()
        var message: String? = null

        val subject = content.subject.replace("\r", "").replace("\n", "").trim()

        if (subject.isEmpty()) {
            fields[FIELD_SUBJECT] = REQUIRED
        } else if (subject.length > MailOptions.MAX_SUBJECT_LENGTH) {
            fields[FIELD_SUBJECT] = TOO_LONG
        }

        val context = HashMap<String, Any?>(system).apply { putAll(sampleVariables) }

        val sources = linkedMapOf(
            FIELD_SUBJECT to (content.subject to false),
            MailTemplateContent.FIELD_PREHEADER to (content.preheader to false),
            MailTemplateContent.FIELD_BODY to (content.body to true),
            MailTemplateContent.FIELD_BUTTON_LABEL to (content.buttonLabel to false),
            MailTemplateContent.FIELD_BUTTON_URL to (content.buttonUrl to false),
            MailTemplateContent.FIELD_FOOTER to (content.footer to true)
        )

        sources.forEach { (name, pair) ->
            val (source, html) = pair

            if (source.isBlank() || fields.containsKey(name)) return@forEach

            try {
                compile(null, source, html).apply(context)
            } catch (e: Exception) {
                fields[name] = TEMPLATE_ERROR
                if (message == null) message = errorMessage(e)
            }
        }

        return ValidationResult(fields, message)
    }

    /** A layout override must keep `{{{content}}}` and compile. */
    fun validateLayout(body: String, system: Map<String, Any?>): ValidationResult {
        if (!CONTENT_SLOT.containsMatchIn(body)) {
            return ValidationResult(mapOf(MailTemplateContent.FIELD_BODY to MISSING_CONTENT), null)
        }

        return try {
            compile(null, body, html = true).apply(HashMap<String, Any?>(system).apply {
                put(FIELD_SUBJECT, "")
                put(LAYOUT_CONTENT, "")
            })

            ValidationResult(emptyMap(), null)
        } catch (e: Exception) {
            ValidationResult(mapOf(MailTemplateContent.FIELD_BODY to TEMPLATE_ERROR), errorMessage(e))
        }
    }

    /** Drops the compiled templates of one override (after a save or a reset). */
    fun evict(owner: String, key: String, variant: String, locale: String) = synchronized(cache) {
        val prefix = "db:$owner/$key/$variant/$locale@"

        cache.keys.removeIf { it.startsWith(prefix) }
    }

    fun evictLayout() = synchronized(cache) {
        cache.keys.removeIf { it.startsWith("db:layout@") }
    }

    private fun compile(cacheKey: String?, source: String, html: Boolean): Template {
        val handlebars = if (html) htmlHandlebars else textHandlebars

        if (cacheKey == null) return handlebars.compileInline(source)

        val key = (if (html) "h:" else "t:") + cacheKey

        synchronized(cache) {
            cache[key]?.let { (cachedSource, template) -> if (cachedSource == source) return template }
        }

        val template = handlebars.compileInline(source)

        synchronized(cache) { cache[key] = source to template }

        return template
    }

    companion object {
        const val REQUIRED = "REQUIRED"
        const val TOO_LONG = "TOO_LONG"
        const val TEMPLATE_ERROR = "TEMPLATE_ERROR"
        const val MISSING_CONTENT = "MISSING_CONTENT"

        const val SYSTEM_WEBSITE_NAME = "websiteName"
        const val SYSTEM_WEBSITE_URL = "websiteUrl"
        const val SYSTEM_WEBSITE_LOGO = "websiteLogo"
        const val SYSTEM_YEAR = "year"
        const val SYSTEM_LOCALE = "locale"

        /** The inline logo every sent mail carries. */
        const val LOGO_CID = "cid:logo"

        const val LAYOUT_RESOURCE = "mail/layout.hbs"

        private const val FIELD_SUBJECT = MailTemplateContent.FIELD_SUBJECT
        private const val LAYOUT_CONTENT = "content"
        private const val MAX_CACHE = 512

        private val CONTENT_SLOT = Regex("\\{\\{\\{\\s*content\\s*}}}")

        /** The variables every template may use without declaring them. */
        fun systemVariables(
            websiteName: String,
            websiteUrl: String,
            locale: String,
            websiteLogo: String = LOGO_CID
        ): Map<String, Any?> = mapOf(
            SYSTEM_WEBSITE_NAME to websiteName,
            SYSTEM_WEBSITE_URL to websiteUrl,
            SYSTEM_WEBSITE_LOGO to websiteLogo,
            SYSTEM_YEAR to Year.now().value.toString(),
            SYSTEM_LOCALE to locale
        )

        internal fun readLayoutResource(): String =
            MailTemplateService::class.java.classLoader.getResourceAsStream(LAYOUT_RESOURCE)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("$LAYOUT_RESOURCE is missing from the build")

        private fun errorMessage(e: Exception): String =
            (e.message ?: e.javaClass.simpleName).lines().firstOrNull { it.isNotBlank() }?.trim()?.take(500)
                ?: e.javaClass.simpleName

        fun MailTemplate.toContent() = MailTemplateContent(
            mode = MailTemplateContent.Mode.parse(mode) ?: MailTemplateContent.Mode.FIELDS,
            subject = subject,
            preheader = preheader.orEmpty(),
            body = body,
            buttonLabel = buttonLabel.orEmpty(),
            buttonUrl = buttonUrl.orEmpty(),
            footer = footer.orEmpty()
        )

        /** The plain-text alternative of a fields-mode mail: body, then `label: url`, then the footer. */
        fun plainText(body: String, buttonLabel: String, buttonUrl: String, footer: String): String {
            val parts = mutableListOf(toPlainText(body))

            if (buttonUrl.isNotBlank()) {
                parts.add(if (buttonLabel.isBlank()) buttonUrl else "$buttonLabel: $buttonUrl")
            }

            if (footer.isNotBlank()) parts.add(toPlainText(footer))

            return parts.filter { it.isNotBlank() }.joinToString("\n\n")
        }

        private val DROPPED_BLOCKS = Regex("(?is)<(head|style|script|title)\\b[^>]*>.*?</\\1\\s*>")
        private val COMMENTS = Regex("(?s)<!--.*?-->")
        private val LINE_BREAKS = Regex("(?i)<br\\s*/?>")
        private val BLOCK_ENDS = Regex("(?i)</(p|div|h[1-6]|li|tr|table|blockquote|ul|ol)\\s*>")
        private val LIST_ITEMS = Regex("(?i)<li\\b[^>]*>")
        private val TAGS = Regex("<[^>]+>")
        private val ENTITIES = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "laquo" to "«", "raquo" to "»"
        )

        /** Tags stripped, `<br>` and block ends as line breaks, entities decoded, blank runs collapsed. */
        fun toPlainText(html: String): String {
            val text = html
                .replace(DROPPED_BLOCKS, "")
                .replace(COMMENTS, "")
                .replace(Regex("\\s*\\n\\s*"), " ")
                .replace(LINE_BREAKS, "\n")
                .replace(LIST_ITEMS, "- ")
                .replace(BLOCK_ENDS, "\n")
                .replace(TAGS, "")
                .let { decodeEntities(it) }

            return text
                .lines()
                .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private fun decodeEntities(text: String): String = ENTITIES.replace(text) { match ->
            val entity = match.groupValues[1]

            when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value

                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value

                else -> NAMED_ENTITIES[entity] ?: match.value
            }
        }
    }
}
