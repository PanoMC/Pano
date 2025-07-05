package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.JsonObjectUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait

@Endpoint
class PanelGetLocaleTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val webClient: WebClient,
    private val uiManager: UIManager,
    private val pluginManager: PluginManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales/:localeId/types/:type/translations", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
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
            TranslationType.PLUGIN -> null
        }

        val translations = mutableListOf<Translation>()

        var originalTranslations: Map<String, Any>

        if (routeType != null) {
            val activatedUI = uiManager.activatedUIList[routeType]!!
            val panelPrefix = if (routeType == Type.PANEL_UI) "/panel" else ""
            val url =
                "http://${activatedUI.host}:${activatedUI.port}${panelPrefix}/${type.name.lowercase()}-api/languages/${locale.code}.json"

            try {
                val response = webClient.getAbs(url).send().coAwait()
                val body = response.bodyAsJsonObject()
                originalTranslations = JsonObjectUtil.flattenJsonObject(body)
            } catch (e: Exception) {
                originalTranslations = mapOf()
            }
        } else {
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
                filterResult.addAll(translations.filter { it.custom != null })
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
}