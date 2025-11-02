package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.model.*
import com.panomc.platform.util.JsonObjectUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait

@Endpoint
class PanelGetLocaleTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val webClient: WebClient,
    private val uiManager: UIManager,
    private val pluginManager: PluginManager,
    private val authProvider: AuthProvider,
    private val i18nManager: I18nManager,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales/:localeId/types/:type/translations", RouteType.GET))

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
                        val pluginTranslations =
                            wrapper.pluginLocales[locale.code] ?: wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE]
                        if (pluginTranslations == null) return@mapNotNull null

                        JsonObjectUtil.flattenJsonObject(pluginTranslations)
                            .map { (key, value) -> "plugins.${wrapper.pluginId}.$key" to value }
                    }
                    .flatten()
                    .toMap()
                    .toMutableMap()

                pluginManager.getPluginWrappers()
                    .mapNotNull { wrapper ->
                        val pluginTranslations =
                            wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE] ?: return@mapNotNull null

                        JsonObjectUtil.flattenJsonObject(pluginTranslations)
                            .map { (key, value) -> "plugins.${wrapper.pluginId}.$key" to value }
                    }.flatten().toMap().forEach { pluginTranslation ->
                        if (originalTranslations[pluginTranslation.key] == null) {
                            originalTranslations[pluginTranslation.key] = pluginTranslation.value
                        }
                    }
            }
        }

        originalTranslations.forEach {
            translations.add(Translation(it.key, it.value.toString(), customTranslationKeyMap[it.key]?.value))
        }

        if (type != TranslationType.PLUGIN && originalTranslations.isNotEmpty() || type == TranslationType.PLUGIN) {
            customTranslationKeyMap
                .filter { !originalTranslations.containsKey(it.key) }
                .forEach {
                    translations.add(Translation(it.value.key, "", it.value.value, true))
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
            mutableMapOf(
                "data" to translations,
                "meta" to mapOf(
                    "totalCount" to translations.count(),
                    "filterCount" to filterResult.count(),
                    "filterResult" to filterResult,
                )
            )
        )
    }

    private data class Translation(
        val key: String,
        val original: String,
        val custom: String? = null,
        val notExists: Boolean = false
    )

    private enum class TranslationFilter {
        ALL,
        ORIGINAL,
        CUSTOM,
        NOT_EXISTS
    }

    private suspend fun getTranslationsFromUI(url: String): MutableMap<String, Any> {
        return try {
            val response = webClient.getAbs(url).send().coAwait()

            if (response.statusCode() == 404) {
                return mutableMapOf()
            }

            val body = response.bodyAsJsonObject()
            JsonObjectUtil.flattenJsonObject(body).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }
}