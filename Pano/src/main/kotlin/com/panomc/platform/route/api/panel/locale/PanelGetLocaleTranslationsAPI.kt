package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.model.*
import com.panomc.platform.model.WholeList
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.util.JsonObjectUtil
import com.panomc.platform.util.PluginDevUtil
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait
import org.pf4j.PluginState

@Endpoint
class PanelGetLocaleTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val webClient: WebClient,
    private val uiManager: UIManager,
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider,
    private val i18nManager: I18nManager,
    private val configManager: ConfigManager,
) : PanelApi() {
    companion object {
        const val SOURCE_PLUGIN = "plugin"
        const val SOURCE_THEME = "theme"

        /**
         * Layers the theme's plugin texts (`<code>.plugins.json`, doc 03 section 5.1) over [original], the
         * flat `plugins.<pluginId>.<key>` map of the plugins' own texts: a theme key replaces the plugin's or
         * adds a new one. A top-level folder of [themeFile] names a plugin by its namespace or by its full id;
         * [plugins] is `pluginId to namespace`. A folder no plugin owns is skipped, as is a missing file
         * (null: no theme, an old theme, a non-200). Returns the keys the theme supplied.
         */
        internal fun applyThemePluginTexts(
            original: MutableMap<String, Any>,
            themeFile: JsonObject?,
            plugins: List<Pair<String, String>>
        ): Set<String> {
            if (themeFile == null) return emptySet()

            val supplied = mutableSetOf<String>()

            // The namespace folder first, so a file that also has the full-id folder lets the id win.
            val byNamespace = plugins.sortedBy { it.first }.groupBy({ it.second }, { it.first })
            val byId = plugins.map { it.first }.toSet()

            val folders = themeFile.fieldNames().sortedBy { if (it in byId && it !in byNamespace) 1 else 0 }

            folders.forEach { folder ->
                val texts = themeFile.getValue(folder) as? JsonObject ?: return@forEach
                val pluginId = if (folder in byNamespace) byNamespace.getValue(folder).first() else folder.takeIf { it in byId }

                if (pluginId == null) return@forEach

                JsonObjectUtil.flattenJsonObject(texts).forEach { (key, value) ->
                    val flatKey = "plugins.$pluginId.$key"

                    original[flatKey] = value
                    supplied.add(flatKey)
                }
            }

            return supplied
        }
    }

    override val paths = listOf(Path("/locales/:localeId/types/:type/translations", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("localeId", numberSchema()))
            .pathParameter(param("type", stringSchema()))
            .queryParameter(
                optionalParam(
                    "filter",
                    arraySchema().items(enumSchema(*TranslationFilter.entries.map { it.name }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTranslations(), context)

        val parameters = getParameters(context)

        val localeId = parameters.pathParameter("localeId").long
        val type = try {
            TranslationType.valueOf(parameters.pathParameter("type").string)
        } catch (e: Exception) {
            throw BadRequest("Invalid type")
        }
        val filter = TranslationFilter.valueOf(
            parameters.queryParameter("filter")?.jsonArray?.first() as String? ?: TranslationFilter.ALL.name
        )

        val sqlClient = getSqlClient()

        val locale = databaseManager.localeDao.byId(localeId, sqlClient) ?: throw NotFound()

        val customTranslations = databaseManager.translationDao.getByLocaleIdAndType(localeId, type, sqlClient)
        val customTranslationKeyMap = customTranslations.associateBy { it.key }

        val routeType = when (type) {
            TranslationType.PANEL -> Type.PANEL_UI
            TranslationType.THEME -> Type.THEME_UI
            else -> null
        }

        val translations = mutableListOf<Translation>()

        var originalTranslations: MutableMap<String, Any>

        when (type) {
            TranslationType.PLATFORM, TranslationType.MC_PLUGIN -> {
                // Get original translations from I18nManager (internal JAR resources)
                originalTranslations = i18nManager.getOriginalTranslations(type, locale.code)
                    .mapValues { it.value as Any }
                    .toMutableMap()
            }

            TranslationType.PANEL, TranslationType.THEME -> {
                // Get original translations from UI (runtime SvelteKit applications)
                val activatedUI = uiManager.activatedUIList[routeType]!!
                val panelPrefix = if (routeType == Type.PANEL_UI) "/panel" else ""
                val url =
                    "http://${activatedUI.host}:${activatedUI.port}${panelPrefix}/${type.name.lowercase()}-api/languages/${locale.code}.json"

                originalTranslations = getTranslationsFromUI(url)

                if (locale.code != AppConstants.DEFAULT_LOCALE_CODE) {
                    val url =
                        "http://${activatedUI.host}:${activatedUI.port}${panelPrefix}/${type.name.lowercase()}-api/languages/${AppConstants.DEFAULT_LOCALE_CODE}.json"

                    val originalTranslationsCopy = originalTranslations.toMap()

                    originalTranslations = getTranslationsFromUI(url)

                    originalTranslationsCopy.forEach {
                        originalTranslations[it.key] = it.value
                    }
                }
            }

            TranslationType.PLUGIN -> {
                // Get original translations from PluginManager (loaded plugins)
                originalTranslations = pluginManager.getPluginWrappers()
                    .mapNotNull { wrapper ->
                        val pluginTranslationsFromDev = if (configManager.config.developmentMode) {
                            val localesDir = PluginDevUtil.getPluginResourceDir(wrapper.pluginId, "locales")
                            if (localesDir != null) {
                                val locales = PluginDevUtil.getPluginLocalesFromDir(localesDir)
                                locales[locale.code] ?: locales[AppConstants.DEFAULT_LOCALE_CODE]
                            } else null
                        } else null

                        val pluginTranslations = pluginTranslationsFromDev
                            ?: wrapper.pluginLocales[locale.code]
                            ?: wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE]

                        if (pluginTranslations == null) return@mapNotNull null

                        JsonObjectUtil.flattenJsonObject(pluginTranslations)
                            .map { (key, value) -> "plugins.${wrapper.pluginId}.$key" to value }
                    }
                    .flatten()
                    .toMap()
                    .toMutableMap()

                pluginManager.getPluginWrappers()
                    .mapNotNull { wrapper ->
                        val pluginTranslationsFromDev = if (configManager.config.developmentMode) {
                            val localesDir = PluginDevUtil.getPluginResourceDir(wrapper.pluginId, "locales")
                            if (localesDir != null) {
                                val locales = PluginDevUtil.getPluginLocalesFromDir(localesDir)
                                locales[AppConstants.DEFAULT_LOCALE_CODE]
                            } else null
                        } else null

                        val pluginTranslations = pluginTranslationsFromDev
                            ?: wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE]
                            ?: return@mapNotNull null

                        JsonObjectUtil.flattenJsonObject(pluginTranslations)
                            .map { (key, value) -> "plugins.${wrapper.pluginId}.$key" to value }
                    }.flatten().toMap().forEach { pluginTranslation ->
                        if (originalTranslations[pluginTranslation.key] == null) {
                            originalTranslations[pluginTranslation.key] = pluginTranslation.value
                        }
                    }
            }
        }

        var themeKeys = emptySet<String>()

        if (type == TranslationType.PLUGIN) {
            // The theme may restate plugin texts (doc 03 section 5.2). Front-end mode "none" has no theme: skip.
            val themeUI = uiManager.activatedUIList[Type.THEME_UI]

            if (themeUI != null) {
                val themeFile = getJsonFromUI(
                    "http://${themeUI.host}:${themeUI.port}/theme-api/languages/${locale.code}.plugins.json"
                )

                themeKeys = applyThemePluginTexts(originalTranslations, themeFile, installedNamespaces())
            }
        }

        originalTranslations.forEach {
            translations.add(
                Translation(
                    it.key, it.value.toString(), customTranslationKeyMap[it.key]?.value,
                    source = if (it.key in themeKeys) SOURCE_THEME else SOURCE_PLUGIN
                )
            )
        }

        if (type != TranslationType.PLUGIN && originalTranslations.isNotEmpty() || type == TranslationType.PLUGIN) {
            customTranslationKeyMap
                .filter { !originalTranslations.containsKey(it.key) }
                .forEach {
                    translations.add(Translation(it.value.key, "", it.value.value, true, SOURCE_PLUGIN))
                }
        }

        val filterResult = mutableListOf<Translation>()

        when (filter) {
            TranslationFilter.CUSTOM -> {
                filterResult.addAll(translations.filter { it.custom != null && !it.notExists })
            }

            TranslationFilter.ORIGINAL -> {
                filterResult.addAll(translations.filter { it.custom == null })
            }

            TranslationFilter.NOT_EXISTS -> {
                filterResult.addAll(translations.filter { it.notExists })
            }

            else -> {}
        }

        return Successful(
            WholeList.response(
                translations.map { it.row(type) },
                mapOf(
                    "filterCount" to filterResult.count(),
                    "filterResult" to filterResult.map { it.row(type) },
                )
            )
        )
    }

    private data class Translation(
        val key: String,
        val original: String,
        val custom: String? = null,
        val notExists: Boolean = false,
        val source: String = SOURCE_PLUGIN
    ) {
        /** Only the plugin list tells where a text comes from; the other lists keep their rows as they were. */
        fun row(type: TranslationType): Any =
            if (type == TranslationType.PLUGIN) PluginRow(key, original, custom, notExists, source) else this
    }

    private data class PluginRow(
        val key: String,
        val original: String,
        val custom: String?,
        val notExists: Boolean,
        val source: String
    )

    private enum class TranslationFilter {
        ALL,
        ORIGINAL,
        CUSTOM,
        NOT_EXISTS
    }

    private suspend fun getTranslationsFromUI(url: String): MutableMap<String, Any> {
        val body = getJsonFromUI(url) ?: return mutableMapOf()

        return JsonObjectUtil.flattenJsonObject(body).toMutableMap()
    }

    /** The JSON object at [url], null for a 404, a non-200, a body that is not an object or any failure. */
    private suspend fun getJsonFromUI(url: String): JsonObject? {
        return try {
            val response = webClient.getAbs(url).send().coAwait()

            if (response.statusCode() != 200) {
                return null
            }

            response.bodyAsJsonObject()
        } catch (e: Exception) {
            null
        }
    }

    /** `pluginId to namespace` of every loaded plugin; a plugin that is not running has no package to ask, so the id rule. */
    private fun installedNamespaces(): List<Pair<String, String>> =
        pluginManager.getPluginWrappers().map { wrapper ->
            val plugin = if (wrapper.pluginState == PluginState.STARTED) wrapper.plugin as? PanoPlugin else null

            wrapper.pluginId to if (plugin != null) PluginNamespace.of(plugin) else PluginNamespace.fromId(wrapper.pluginId)
        }
}