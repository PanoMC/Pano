package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.template.MailDefinition
import com.panomc.platform.mail.template.MailTemplateContent
import com.panomc.platform.mail.template.MailTemplateRegistry
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.platform.mail.template.VariantDefinition
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.SqlClient
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/** What the panel's mail template endpoints share: lookups, the template object, preview variables. */
@Lazy
@Component
class PanelMailTemplateSupport(
    val registry: MailTemplateRegistry,
    val service: MailTemplateService,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val mailManager: MailManager
) {
    data class Target(
        val owner: String,
        val mail: MailDefinition,
        val variant: VariantDefinition,
        val locale: String
    )

    /**
     * The registered mail / variant and an existing locale.
     *
     * @throws NotExists for an unknown owner, key or variant, or a locale missing from the locale table
     */
    suspend fun target(owner: String, key: String, variant: String, locale: String, sqlClient: SqlClient): Target {
        val mail = registry.mail(owner, key) ?: throw NotExists()
        val variantDefinition = mail.variant(variant) ?: throw NotExists()

        if (!databaseManager.localeDao.existsByCode(locale, sqlClient)) throw NotExists()

        return Target(owner, mail, variantDefinition, locale)
    }

    /** The admin's own language: names and labels are answered in it. */
    suspend fun adminLocale(userId: Long, sqlClient: SqlClient): String =
        databaseManager.userDao.getById(userId, sqlClient)?.localeCode?.takeIf { it.isNotBlank() }
            ?: configManager.config.locale

    /**
     * System variables for a preview shown in the panel: the logo is embedded as a `data:` URI (the mail's `cid:logo`
     * means nothing there, and a URL would resolve against the panel's origin inside the sandboxed frame).
     */
    fun previewSystemVariables(locale: String): Map<String, Any?> {
        val config = configManager.config

        return MailTemplateService.systemVariables(config.websiteName, config.websiteUrl, locale, previewLogo())
    }

    /** The logo previews show; the public URL when the logo cannot be read. */
    fun previewLogo(): String = runCatching { mailManager.logoDataUri() }.getOrDefault(PREVIEW_LOGO)

    val websiteName: String
        get() = configManager.config.websiteName

    /** Throws [InvalidFields] when [content] does not validate; with [templateErrorsOnly] only render errors count. */
    fun requireValid(content: MailTemplateContent, target: Target, templateErrorsOnly: Boolean = false) {
        val result = service.validate(content, target.variant.sampleVariables(), previewSystemVariables(target.locale))

        val fields = if (templateErrorsOnly) {
            result.fields.filterValues { it == MailTemplateService.TEMPLATE_ERROR }
        } else {
            result.fields
        }

        if (fields.isNotEmpty()) throw invalid(fields, result.message)
    }

    companion object {
        const val PREVIEW_LOGO = "/api/v1/website-logo"

        fun invalid(fields: Map<String, String>, message: String?): InvalidFields =
            InvalidFields(fields, if (message == null) mapOf() else mapOf("message" to message))

        /**
         * The variables a preview renders with: the variant's [samples], each overridden by [overrides] (the preview
         * window's own test data). Undeclared keys are kept; JSON objects and arrays become plain maps and lists.
         */
        fun previewVariables(samples: Map<String, Any?>, overrides: JsonObject?): Map<String, Any?> {
            if (overrides == null || overrides.isEmpty) return samples

            return LinkedHashMap(samples).apply {
                overrides.forEach { (name, value) -> put(name, plain(value)) }
            }
        }

        private fun plain(value: Any?): Any? = when (value) {
            is JsonObject -> value.associateTo(LinkedHashMap<String, Any?>()) { (name, item) -> name to plain(item) }
            is JsonArray -> value.map { plain(it) }
            else -> value
        }

        /** The panel's "template" object as a JSON schema; every field but `subject` and `body` is optional. */
        fun templateSchema(): ObjectSchemaBuilder = objectSchema()
            .optionalProperty("mode", stringSchema())
            .requiredProperty("subject", stringSchema())
            .optionalProperty("preheader", stringSchema())
            .requiredProperty("body", stringSchema())
            .optionalProperty("buttonLabel", stringSchema())
            .optionalProperty("buttonUrl", stringSchema())
            .optionalProperty("footer", stringSchema())

        /** Reads a template object; an unknown `mode` is `INVALID_FIELDS { mode: "INVALID" }`. */
        fun templateFrom(json: JsonObject): MailTemplateContent {
            val modeValue = json.getString("mode")
            val mode = if (modeValue.isNullOrBlank()) MailTemplateContent.Mode.FIELDS
            else MailTemplateContent.Mode.parse(modeValue) ?: throw InvalidFields(mapOf("mode" to "INVALID"))

            return MailTemplateContent(
                mode = mode,
                subject = json.getString("subject").orEmpty(),
                preheader = json.getString("preheader").orEmpty(),
                body = json.getString("body").orEmpty(),
                buttonLabel = json.getString("buttonLabel").orEmpty(),
                buttonUrl = json.getString("buttonUrl").orEmpty(),
                footer = json.getString("footer").orEmpty()
            )
        }
    }
}
