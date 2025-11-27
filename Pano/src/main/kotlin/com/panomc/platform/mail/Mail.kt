package com.panomc.platform.mail

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.i18n.I18nManager
import java.util.concurrent.ConcurrentHashMap

interface Mail {
    val templatePath: String

    val subject: String

    suspend fun generateParameters(systemParameters: MailManager.Companion.SystemParameters, i18nManager: I18nManager, locale: String): MailParameters

    /**
     * Gets the compiled Handlebars template for this mail.
     * The template is compiled from the template content provided by getTemplateContent()
     * and cached for subsequent calls.
     *
     * @param handlebars Handlebars instance to use for compilation
     * @return Compiled Handlebars template
     */
    fun getTemplate(handlebars: Handlebars): Template {
        val cacheKey = this::class.java
        val cached = templateCache[cacheKey]
        if (cached != null) {
            return cached
        }
        
        val templateContent = getTemplateContent()
        val compiled = handlebars.compileInline(templateContent)
        templateCache[cacheKey] = compiled
        return compiled
    }

    /**
     * Gets the raw template content as a string.
     * This content will be compiled using Handlebars inline compilation.
     *
     * @return Raw template content string
     */
    fun getTemplateContent(): String {
        val resourceStream = javaClass.classLoader.getResourceAsStream(templatePath)
            ?: throw IllegalStateException("Template not found: $templatePath")
        return resourceStream.bufferedReader().use { it.readText() }
    }

    /**
     * Gets all translations within a translation group using the provided I18nManager and locale.
     * Automatically discovers all keys under the group prefix and removes the prefix from result keys.
     * Each discovered key can have its own variables for template rendering.
     *
     * @param i18nManager The I18nManager instance to use for translations
     * @param locale The locale code (e.g., "en-US", "tr")
     * @param groupPrefix The translation group prefix (e.g., "mail.activation")
     * @param variables Map where keys are the result identifiers (without prefix) and values are variable maps for each key
     * @return Map where keys are the identifiers without group prefix and values are the translated strings.
     */
    suspend fun getTranslations(
        i18nManager: I18nManager,
        locale: String,
        groupPrefix: String,
        variables: Map<String, Map<String, Any?>> = emptyMap()
    ): Map<String, String> {
        // Get all translations for the locale
        val allTranslations = i18nManager.getTranslations(TranslationType.PLATFORM, locale)

        // Filter keys that start with the group prefix and remove the prefix
        return allTranslations.keys
            .filter { it.startsWith("$groupPrefix.") }
            .associate { fullKey ->
                val resultKey = fullKey.removePrefix("$groupPrefix.")
                val keyVariables = variables[resultKey] ?: emptyMap()
                val translation = i18nManager.translate(TranslationType.PLATFORM, locale, fullKey, keyVariables)!!

                resultKey to translation
            }
    }

    companion object {
        // Cache for compiled templates, keyed by Mail class
        private val templateCache = ConcurrentHashMap<Class<*>, Template>()
    }
}