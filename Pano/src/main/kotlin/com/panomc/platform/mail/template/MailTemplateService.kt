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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.text.Normalizer
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
    private val defaultLayoutProvider: () -> String,
    private val layoutStore: MailLayoutStore
) {
    /** Without a database: bundled layouts and the mails' own layout defaults only (tests, plugin tests). */
    internal constructor(
        registry: MailTemplateRegistry,
        overrides: suspend (sqlClient: SqlClient?, owner: String, key: String, variant: String, locale: String) -> MailTemplate?,
        defaultLayoutProvider: () -> String
    ) : this(registry, overrides, defaultLayoutProvider, MailLayoutStore.EMPTY)

    @Autowired
    constructor(registry: MailTemplateRegistry, databaseManager: ObjectProvider<DatabaseManager>) : this(
        registry,
        { sqlClient, owner, key, variant, locale ->
            sqlClient?.let { databaseManager.getObject().mailTemplateDao.get(owner, key, variant, locale, it) }
        },
        { readLayoutResource() },
        MailLayoutStore.database(databaseManager)
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

    /** A layout's body as mails are wrapped in it. */
    data class Layout(
        val body: String,
        val customized: Boolean,
        internal val cacheId: String,
        val id: String = MailTemplateIndex.DEFAULT_LAYOUT
    )

    /**
     * One layout the panel lists: `core:default`, a layout core or a started plugin ships, or a custom one
     * (owner `custom`, only in the database).
     */
    data class LayoutInfo(
        val id: String,
        val owner: String,
        val key: String,
        val pluginId: String?,
        val pluginName: String?,
        /** Localised names of a bundled layout; empty for custom ones. */
        private val names: Map<String, String>,
        /** The name an admin gave a custom layout. */
        private val customName: String?,
        /** Core or a plugin ships it: it can be reset, never deleted. */
        val builtIn: Boolean,
        /** A bundled layout with a panel override. */
        val customized: Boolean
    ) {
        val deletable get() = !builtIn

        fun name(locale: String): String = customName ?: MailTemplateIndex.localized(names, locale, key)
    }

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

    /** The bundled `mail/layout.hbs` (`core:default` without its override). */
    fun defaultLayout(): String = defaultLayout

    /** Every layout: `core:default` first, then the ones core and the plugins ship, then custom ones by name. */
    suspend fun layouts(sqlClient: SqlClient?): List<LayoutInfo> {
        val rows = layoutStore.layouts(sqlClient)
        val overridden = rows.map { it.owner to it.layoutKey }.toSet()

        val main = LayoutInfo(
            MailTemplateIndex.DEFAULT_LAYOUT, MailTemplateRegistry.CORE, DEFAULT_LAYOUT_KEY, null, null,
            DEFAULT_LAYOUT_NAMES, null, builtIn = true,
            customized = (MailTemplateRegistry.CORE to DEFAULT_LAYOUT_KEY) in overridden
        )

        val bundled = registry.owners().flatMap { owner ->
            owner.index.layouts
                .filter { !(owner.id == MailTemplateRegistry.CORE && it.key == DEFAULT_LAYOUT_KEY) }
                .map { layout ->
                    LayoutInfo(
                        "${owner.id}:${layout.key}", owner.id, layout.key, owner.pluginId, owner.pluginName,
                        layout.name, null, builtIn = true, customized = (owner.id to layout.key) in overridden
                    )
                }
        }

        val custom = rows.filter { it.owner == CUSTOM_OWNER }
            .sortedBy { it.name.lowercase() }
            .map {
                LayoutInfo(
                    "$CUSTOM_OWNER:${it.layoutKey}", CUSTOM_OWNER, it.layoutKey, null, null,
                    emptyMap(), it.name.ifBlank { it.layoutKey }, builtIn = false, customized = false
                )
            }

        return listOf(main) + bundled + custom
    }

    suspend fun layoutInfo(id: String, sqlClient: SqlClient?): LayoutInfo? = layouts(sqlClient).firstOrNull { it.id == id }

    /** The bundled body of a built-in layout, null for a custom or unknown one. */
    fun bundledLayout(id: String): String? {
        if (id == MailTemplateIndex.DEFAULT_LAYOUT) return defaultLayout

        val (owner, key) = splitLayoutId(id) ?: return null

        if (owner == CUSTOM_OWNER) return null

        return registry.owner(owner)?.readLayout(key)
    }

    /** The layout [id] as mails get it (its override, else its bundled body), null when it does not exist. */
    suspend fun layout(id: String, sqlClient: SqlClient?): Layout? {
        val (owner, key) = splitLayoutId(id) ?: return null
        val row = layoutStore.layout(sqlClient, owner, key)

        if (owner == CUSTOM_OWNER) {
            return row?.let { Layout(it.body, false, "db:layout:$id@${it.updatedAt}", id) }
        }

        val bundled = bundledLayout(id) ?: return null

        return if (row != null) Layout(row.body, true, "db:layout:$id@${row.updatedAt}", id)
        else Layout(bundled, false, "res:layout:$id", id)
    }

    /** What the mail is set to: the admin's choice, else the mail's own default (a layout id or `none`). */
    suspend fun mailLayoutChoice(owner: String, key: String, sqlClient: SqlClient?): String =
        layoutStore.mailSetting(sqlClient, owner, key)
            ?: registry.mail(owner, key)?.layout
            ?: MailTemplateIndex.DEFAULT_LAYOUT

    /**
     * The layout the mail is sent with: [override] (an unsaved choice in the panel) or the mail's choice. `none`
     * stays `none`; a layout that no longer exists (a deleted custom one, an unloaded plugin's) falls back to
     * `core:default`.
     */
    suspend fun effectiveLayoutId(owner: String, key: String, sqlClient: SqlClient?, override: String? = null): String {
        val choice = override ?: mailLayoutChoice(owner, key, sqlClient)

        if (choice == MailTemplateIndex.NO_LAYOUT || choice == MailTemplateIndex.DEFAULT_LAYOUT) return choice

        if (layout(choice, sqlClient) != null) return choice

        logger.warn("Mail $owner/$key uses the layout '$choice' that does not exist; it falls back to ${MailTemplateIndex.DEFAULT_LAYOUT}")

        return MailTemplateIndex.DEFAULT_LAYOUT
    }

    /** The layout the mail is wrapped in, null for `none`. */
    suspend fun layoutForMail(owner: String, key: String, sqlClient: SqlClient?, override: String? = null): Layout? {
        val id = effectiveLayoutId(owner, key, sqlClient, override)

        if (id == MailTemplateIndex.NO_LAYOUT) return null

        return layout(id, sqlClient) ?: layout(MailTemplateIndex.DEFAULT_LAYOUT, sqlClient)
    }

    /** The effective layout of every registered mail (`owner` to `key`), from one read of the settings. */
    suspend fun effectiveLayouts(sqlClient: SqlClient?): Map<Pair<String, String>, String> {
        val existing = layouts(sqlClient).map { it.id }.toSet()
        val settings = layoutStore.mailSettings(sqlClient).associate { (it.owner to it.mailKey) to it.layout }

        return registry.owners().flatMap { owner ->
            owner.index.mails.map { mail ->
                val choice = settings[owner.id to mail.key] ?: mail.layout
                val effective = if (choice == MailTemplateIndex.NO_LAYOUT || choice in existing) choice
                else MailTemplateIndex.DEFAULT_LAYOUT

                (owner.id to mail.key) to effective
            }
        }.toMap()
    }

    /** A free custom layout key for [name]: its slug, with `-2`, `-3`, … when taken. */
    suspend fun newCustomLayoutKey(name: String, sqlClient: SqlClient?): String {
        val taken = layoutStore.layouts(sqlClient).filter { it.owner == CUSTOM_OWNER }.map { it.layoutKey }.toSet()
        val base = slug(name)

        if (base !in taken) return base

        return generateSequence(2) { it + 1 }.map { "${base.take(MAX_LAYOUT_KEY - 4)}-$it" }.first { it !in taken }
    }

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

        val layout = if (resolved.content.mode == MailTemplateContent.Mode.FIELDS) layoutForMail(owner, key, sqlClient) else null

        return render(resolved.content, layout, variables, system, resolved.cacheId)
    }

    /**
     * Renders [content] with [system] + [variables]. In fields mode the results go into [layout]; without one (`none`)
     * the rendered body is the whole mail. [cacheId] (null for unsaved content) keys the compiled templates.
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

        val html = if (layout == null) body else compile(layout.cacheId, layout.body, html = true).apply(layoutContext)

        return RenderedMail(subject, html, plainText(body, buttonLabel, buttonUrl, footer))
    }

    /**
     * [content] as one complete HTML template, the starting point of the panel's advanced (HTML) mode: the layout with
     * the field templates put into its slots as they are (their `{{variables}}` stay, so the result is still a
     * template) and the system variables left as `{{name}}`. HTML mode content, and any content without a layout
     * (`none`), is the body as it is.
     */
    fun compose(content: MailTemplateContent, layout: Layout?): String {
        if (content.mode == MailTemplateContent.Mode.HTML || layout == null) return content.body

        val slots = HashMap<String, Any?>().apply {
            listOf(SYSTEM_WEBSITE_NAME, SYSTEM_WEBSITE_URL, SYSTEM_WEBSITE_LOGO, SYSTEM_YEAR, SYSTEM_LOCALE)
                .forEach { put(it, "{{$it}}") }
            put(FIELD_SUBJECT, content.subject.trim())
            put(MailTemplateContent.FIELD_PREHEADER, content.preheader.trim())
            put(LAYOUT_CONTENT, content.body.trim())
            put(MailTemplateContent.FIELD_BUTTON_LABEL, content.buttonLabel.trim().ifBlank { content.buttonUrl.trim() })
            put(MailTemplateContent.FIELD_BUTTON_URL, content.buttonUrl.trim())
            put(MailTemplateContent.FIELD_FOOTER, content.footer.trim())
        }

        return textHandlebars.compileInline(layout.body).apply(slots)
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

        cache.keys.removeIf { it.substringAfter(':').startsWith(prefix) }
    }

    /** Drops the compiled templates of one layout's override (after a save, a reset or a delete). */
    fun evictLayout(id: String) = synchronized(cache) {
        val prefix = "db:layout:$id@"

        cache.keys.removeIf { it.substringAfter(':').startsWith(prefix) }
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

        /** The owner of admin-made layouts. */
        const val CUSTOM_OWNER = "custom"

        const val DEFAULT_LAYOUT_KEY = "default"

        const val MAX_LAYOUT_NAME = 100
        private const val MAX_LAYOUT_KEY = 64

        private val DEFAULT_LAYOUT_NAMES = mapOf("en-US" to "Main layout", "tr" to "Ana düzen", "ru" to "Основной макет")

        private val logger = LoggerFactory.getLogger(MailTemplateService::class.java)

        /** `owner` and `key` of a layout id, null when it is not one. */
        fun splitLayoutId(id: String): Pair<String, String>? {
            if (!MailTemplateIndex.LAYOUT_ID_PATTERN.matches(id)) return null

            return id.substringBeforeLast(':') to id.substringAfterLast(':')
        }

        /** [name] as a layout key: lower case ASCII letters and digits apart by `-`; `layout` when nothing is left. */
        fun slug(name: String): String {
            val ascii = Normalizer.normalize(
                name.replace('ı', 'i').replace('İ', 'I').replace('ß', 's'),
                Normalizer.Form.NFD
            ).replace(Regex("\\p{M}+"), "")

            return ascii.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(MAX_LAYOUT_KEY)
                .trim('-')
                .ifEmpty { "layout" }
        }

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
        private val CELL_ENDS = Regex("(?i)</t[dh]\\s*>")
        private val TAGS = Regex("<[^>]+>")
        private val ENTITIES = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "laquo" to "«", "raquo" to "»"
        )

        /** Tags stripped, `<br>` and block ends (table rows too) as line breaks, table cells apart by a space, entities decoded, blank runs collapsed. */
        fun toPlainText(html: String): String {
            val text = html
                .replace(DROPPED_BLOCKS, "")
                .replace(COMMENTS, "")
                .replace(Regex("\\s*\\n\\s*"), " ")
                .replace(LINE_BREAKS, "\n")
                .replace(LIST_ITEMS, "- ")
                .replace(BLOCK_ENDS, "\n")
                .replace(CELL_ENDS, " ")
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
