package com.panomc.platform.i18n

import com.github.jknack.handlebars.Handlebars
import com.panomc.platform.AppConstants
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.util.JsonObjectUtil
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * I18n Manager - Handles internal platform translations from JAR resources.
 *
 * This manager is responsible for loading and managing translations that are embedded within the JAR file.
 * It only handles PLATFORM and MC_PLUGIN translation types, as these are the only internal translations
 * bundled in the resources/locales directory.
 *
 * **Why only PLATFORM and MC_PLUGIN?**
 * - PLATFORM: Core platform translations embedded in resources/locales/platform/
 * - MC_PLUGIN: Minecraft plugin integration translations embedded in resources/locales/mcplugin/
 * - PANEL: Loaded from Panel UI (runtime SvelteKit application) - not handled here
 * - THEME: Loaded from Theme UI (runtime SvelteKit application) - not handled here
 * - PLUGIN: Loaded from installed plugins via PluginManager - not handled here
 *
 * **How it works:**
 * 1. On startup (after database initialization), loads all translation JSON files from resources
 * 2. Flattens nested JSON structures to key-value pairs
 * 3. When getting a translation, first checks for custom overrides in the database
 * 4. Falls back to original translation from resources if no custom override exists
 * 5. Falls back to default locale (en-US) if translation not found in requested locale
 *
 * **Usage:**
 * ```kotlin
 * // Get a single translation (with custom override support)
 * val translation = i18nManager.getTranslation(TranslationType.PLATFORM, "en-US", "welcome.message")
 *
 * // Get all translations for a locale (with custom overrides)
 * val translations = i18nManager.getTranslations(TranslationType.PLATFORM, "tr")
 *
 * // Get original translation without custom overrides
 * val original = i18nManager.getOriginalTranslation(TranslationType.PLATFORM, "en-US", "welcome.message")
 * ```
 *
 * @property databaseManager Used to fetch custom translation overrides from the database
 */
@Lazy
@Component
class I18nManager(
    @get:Lazy private val databaseManager: DatabaseManager,
    @get:Lazy private val configManager: ConfigManager
) {
    @Autowired
    private lateinit var logger: Logger

    // Structure: Map<TranslationType, Map<LocaleCode, Map<Key, Value>>>
    private val translations = mutableMapOf<TranslationType, MutableMap<String, Map<String, String>>>()
    
    // Handlebars instance for rendering translations with variables
    private val handlebars by lazy { Handlebars() }

    suspend fun init() {
        logger.info("Loading platform translations from resources")

        loadTranslationsFromResources(TranslationType.PLATFORM, "locales/platform")
        loadTranslationsFromResources(TranslationType.MC_PLUGIN, "locales/mcplugin")

        logger.info("Successfully loaded translations for ${translations.size} types")
    }

    private fun loadTranslationsFromResources(type: TranslationType, resourcePath: String) {
        val typeTranslations = mutableMapOf<String, Map<String, String>>()

        AppConstants.AVAILABLE_LOCALES.forEach { localeCode ->
            val resourceFile = "$resourcePath/$localeCode.json"

            try {
                val resourceStream = javaClass.classLoader.getResourceAsStream(resourceFile)

                if (resourceStream != null) {
                    val jsonContent = resourceStream.bufferedReader().use { it.readText() }
                    val jsonObject = JsonObject(jsonContent)

                    val flattenedTranslations = JsonObjectUtil.flattenJsonObject(jsonObject)
                        .mapValues { it.value.toString() }

                    typeTranslations[localeCode] = flattenedTranslations

                    logger.info("Loaded ${flattenedTranslations.size} translations for $type - $localeCode")
                } else {
                    logger.warn("Translation file not found: $resourceFile")
                    typeTranslations[localeCode] = emptyMap()
                }
            } catch (e: Exception) {
                logger.error("Failed to load translations from $resourceFile", e)
                typeTranslations[localeCode] = emptyMap()
            }
        }

        translations[type] = typeTranslations
    }

    /**
     * Gets a translation by type, locale code, and key.
     * If a custom translation exists in the database, it returns that instead of the original.
     *
     * @param type Translation type (PLATFORM or MC_PLUGIN)
     * @param localeCode Locale code (e.g., "en-US", "tr")
     * @param key Translation key
     * @return The translation value, custom if available, otherwise original. Returns null if not found.
     */
    suspend fun getTranslation(type: TranslationType, localeCode: String, key: String): String? {
        // First try to get custom translation from database
        val customTranslations = databaseManager.translationDao.getByLocaleCodeAndType(
            localeCode,
            type,
            databaseManager.getSqlClient()
        )

        val customTranslation = customTranslations.find { it.key == key }
        if (customTranslation != null) {
            return customTranslation.value
        }

        // Fall back to original translation from resources
        val localeTranslations = translations[type]?.get(localeCode)

        if (localeTranslations != null && localeTranslations.containsKey(key)) {
            return localeTranslations[key]
        }

        // If not found in requested locale, try default locale
        if (localeCode != AppConstants.DEFAULT_LOCALE_CODE) {
            val defaultLocaleTranslations = translations[type]?.get(AppConstants.DEFAULT_LOCALE_CODE)
            return defaultLocaleTranslations?.get(key)
        }

        return null
    }

    /**
     * Gets all translations for a specific type and locale code.
     * Custom translations override original ones.
     *
     * @param type Translation type (PLATFORM or MC_PLUGIN)
     * @param localeCode Locale code (e.g., "en-US", "tr")
     * @return Map of all translations (key -> value)
     */
    suspend fun getTranslations(type: TranslationType, localeCode: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Start with original translations
        val originalTranslations = translations[type]?.get(localeCode) ?: emptyMap()
        result.putAll(originalTranslations)

        // If not default locale, add missing keys from default locale
        if (localeCode != AppConstants.DEFAULT_LOCALE_CODE) {
            val defaultTranslations = translations[type]?.get(AppConstants.DEFAULT_LOCALE_CODE) ?: emptyMap()
            defaultTranslations.forEach { (key, value) ->
                if (!result.containsKey(key)) {
                    result[key] = value
                }
            }
        }

        // Override with custom translations from database
        val customTranslations = databaseManager.translationDao.getByLocaleCodeAndType(
            localeCode,
            type,
            databaseManager.getSqlClient()
        )

        customTranslations.forEach { translation ->
            result[translation.key] = translation.value
        }

        return result
    }

    /**
     * Gets the original (non-customized) translation from resources.
     *
     * @param type Translation type (PLATFORM or MC_PLUGIN)
     * @param localeCode Locale code (e.g., "en-US", "tr")
     * @param key Translation key
     * @return The original translation value or null if not found
     */
    fun getOriginalTranslation(type: TranslationType, localeCode: String, key: String): String? {
        val localeTranslations = translations[type]?.get(localeCode)

        if (localeTranslations != null && localeTranslations.containsKey(key)) {
            return localeTranslations[key]
        }

        // If not found in requested locale, try default locale
        if (localeCode != AppConstants.DEFAULT_LOCALE_CODE) {
            val defaultLocaleTranslations = translations[type]?.get(AppConstants.DEFAULT_LOCALE_CODE)
            return defaultLocaleTranslations?.get(key)
        }

        return null
    }

    /**
     * Gets all original translations (without custom overrides) for a specific type and locale code.
     *
     * @param type Translation type (PLATFORM or MC_PLUGIN)
     * @param localeCode Locale code (e.g., "en-US", "tr")
     * @return Map of all original translations (key -> value)
     */
    fun getOriginalTranslations(type: TranslationType, localeCode: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Get translations for requested locale
        val originalTranslations = translations[type]?.get(localeCode) ?: emptyMap()
        result.putAll(originalTranslations)

        // If not default locale, add missing keys from default locale
        if (localeCode != AppConstants.DEFAULT_LOCALE_CODE) {
            val defaultTranslations = translations[type]?.get(AppConstants.DEFAULT_LOCALE_CODE) ?: emptyMap()
            defaultTranslations.forEach { (key, value) ->
                if (!result.containsKey(key)) {
                    result[key] = value
                }
            }
        }

        return result
    }
    /**
     * Returns translations (including custom overrides) grouped by locale code.
     * Structure: Map<LocaleCode, Map<Key, Value>>
     */
    suspend fun getTranslationsByLocale(type: TranslationType): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, Map<String, String>>()

        for (localeCode in AppConstants.AVAILABLE_LOCALES) {
            result[localeCode] = getTranslations(type, localeCode)
        }

        return result
    }

    /**
     * Translates a key with variable substitution using Handlebars template engine.
     * The translation string can contain Handlebars syntax like {{variableName}}.
     *
     * @param type Translation type (PLATFORM or MC_PLUGIN)
     * @param localeCode Locale code (e.g., "en-US", "tr")
     * @param key Translation key
     * @param variables Map of variables to be used in the template (default: empty map)
     * @return The rendered translation string or null if translation not found
     *
     * @example
     * ```kotlin
     * // Translation: "Welcome, {{username}}!"
     * val result = i18nManager.translate(
     *     TranslationType.PLATFORM,
     *     "en-US",
     *     "welcome.message",
     *     mapOf("username" to "John")
     * )
     * // Result: "Welcome, John!"
     * ```
     */
    suspend fun translate(
        type: TranslationType,
        localeCode: String,
        key: String,
        variables: Map<String, Any> = emptyMap()
    ): String? {
        val translationTemplate = getTranslation(type, localeCode, key) ?: return null

        // If no variables provided, return the translation as-is
        if (variables.isEmpty()) {
            return translationTemplate
        }

        return try {
            val template = handlebars.compileInline(translationTemplate)
            template.apply(variables)
        } catch (e: Exception) {
            logger.error("Failed to render translation template for key: $key", e)
            translationTemplate // Return original template on error
        }
    }

    suspend fun getLocaleCode(console: Boolean, adminId: Long?, userId: Long): String {
        if (console) {
            return configManager.config.locale
        }

        val sqlClient = databaseManager.getSqlClient()

        if (adminId != null) {
            val adminLocale = databaseManager.userDao.getLocaleCodeById(adminId, sqlClient)

            if (adminLocale != null) {
                return adminLocale
            }

            return configManager.config.locale
        }

        val userLocale = databaseManager.userDao.getLocaleCodeById(userId, sqlClient)

        if (userLocale != null) {
            return userLocale
        }

        return configManager.config.locale
    }
}

