package com.panomc.platform.route.api.panel

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.AccessActivityLogsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.JsonObjectUtil
import com.panomc.platform.util.PluginDevUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.pf4j.PluginState
import java.util.*
import kotlin.math.ceil

@Endpoint
class PanelGetActivityLogsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val webClient: WebClient,
    private val uiManager: UIManager,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/logs/activity", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", intSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("locale", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 1
        val search = parameters.queryParameter("search")?.string?.trim()?.takeIf { it.isNotEmpty() }
        var localeCode = parameters.queryParameter("locale")?.string?.trim()?.takeIf { it.isNotEmpty() }
            ?: AppConstants.DEFAULT_LOCALE_CODE

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val hasPermission = authProvider.hasPermission(AccessActivityLogsPermission(), context)

        val sqlClient = getSqlClient()

        if (!databaseManager.localeDao.existsByCode(localeCode, sqlClient)) {
            localeCode = AppConstants.DEFAULT_LOCALE_CODE
        }

        var platforms: List<PanelActivityLog> = emptyList()
        val totalCount: Long

        if (search == null) {
            totalCount = if (hasPermission)
                databaseManager.panelActivityLogDao.count(sqlClient)
            else
                databaseManager.panelActivityLogDao.count(userId, sqlClient)
        } else {
            val translations = getPanelSearchTranslations(localeCode, sqlClient)
            val allLogs = if (hasPermission)
                databaseManager.panelActivityLogDao.getAll(sqlClient)
            else
                databaseManager.panelActivityLogDao.byUserId(userId, sqlClient)

            val normalizedSearchQuery = normalizeSearchText(search, localeCode)
            val filteredLogs = allLogs.filter {
                matchesSearch(it, normalizedSearchQuery, localeCode, translations)
            }

            totalCount = filteredLogs.size.toLong()
            val offset = ((page - 1) * PAGE_SIZE).toInt()
            platforms = filteredLogs.drop(offset).take(PAGE_SIZE.toInt())
        }

        var totalPage = ceil(totalCount.toDouble() / PAGE_SIZE).toLong()

        if (totalPage < 1) {
            totalPage = 1
        }

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        if (search == null) {
            platforms = if (hasPermission)
                databaseManager.panelActivityLogDao.getAll(page, sqlClient)
            else
                databaseManager.panelActivityLogDao.byUserId(userId, page, sqlClient)
        }

        val response = mutableMapOf(
            "data" to platforms,
            "meta" to mapOf(
                "filteredCount" to totalCount,
                "totalCount" to totalCount,
                "totalPage" to totalPage
            )
        )

        return Successful(
            response,
        )
    }

    private suspend fun getPanelSearchTranslations(
        localeCode: String,
        sqlClient: SqlClient
    ): Map<String, String> {
        val translations = getPanelOriginalTranslations(localeCode)
        val customPanelTranslations = databaseManager.translationDao
            .getByLocaleCodeAndType(localeCode, TranslationType.PANEL, sqlClient)
            .associate { it.key to it.value }
        val customPluginTranslations = databaseManager.translationDao
            .getByLocaleCodeAndType(localeCode, TranslationType.PLUGIN, sqlClient)
            .associate { it.key to it.value }

        translations.putAll(customPanelTranslations)
        translations.putAll(getPluginTranslations(localeCode, customPluginTranslations))

        return translations
    }

    private suspend fun getPanelOriginalTranslations(localeCode: String): MutableMap<String, String> {
        val activatedUI = uiManager.activatedUIList[Type.PANEL_UI] ?: return mutableMapOf()
        val localeUrl = "http://${activatedUI.host}:${activatedUI.port}/panel/panel-api/languages/$localeCode.json"
        val defaultUrl =
            "http://${activatedUI.host}:${activatedUI.port}/panel/panel-api/languages/${AppConstants.DEFAULT_LOCALE_CODE}.json"

        val localeTranslations = getTranslationsFromUI(localeUrl)

        if (localeCode == AppConstants.DEFAULT_LOCALE_CODE) {
            return localeTranslations
        }

        val defaultTranslations = getTranslationsFromUI(defaultUrl)
        defaultTranslations.putAll(localeTranslations)

        return defaultTranslations
    }

    private suspend fun getTranslationsFromUI(url: String): MutableMap<String, String> {
        return try {
            val response = webClient.getAbs(url).send().coAwait()

            if (response.statusCode() == 404) {
                return mutableMapOf()
            }

            val body = response.bodyAsJsonObject() ?: return mutableMapOf()
            JsonObjectUtil.flattenJsonObject(body)
                .mapValues { it.value?.toString() ?: "" }
                .toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun getPluginTranslations(
        localeCode: String,
        customPluginTranslations: Map<String, String>
    ): Map<String, String> {
        return pluginManager.getPluginWrappers()
            .filter { it.pluginState == PluginState.STARTED }
            .mapNotNull { wrapper ->
                val pluginTranslationsFromDev = if (configManager.config.developmentMode) {
                    val localesDir = PluginDevUtil.getPluginResourceDir(wrapper.pluginId, "locales")
                    if (localesDir != null) {
                        val locales = PluginDevUtil.getPluginLocalesFromDir(localesDir)
                        locales[localeCode] ?: locales[AppConstants.DEFAULT_LOCALE_CODE]
                    } else null
                } else null

                val pluginTranslations = pluginTranslationsFromDev
                    ?: wrapper.pluginLocales[localeCode]
                    ?: wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE]

                if (pluginTranslations == null) {
                    return@mapNotNull null
                }

                JsonObjectUtil.flattenJsonObject(pluginTranslations)
                    .map { (key, value) ->
                        val fullKey = "plugins.${wrapper.pluginId}.$key"
                        fullKey to (customPluginTranslations[fullKey] ?: value.toString())
                    }
            }
            .flatten()
            .toMap()
    }

    private fun matchesSearch(
        log: PanelActivityLog,
        normalizedSearchQuery: String,
        localeCode: String,
        translations: Map<String, String>
    ): Boolean {
        val translation = getActivityLogTranslation(log, translations)
        val type = log.type ?: ""
        val details = log.details.encode()
        val searchableText = normalizeSearchText("$translation $type $details", localeCode)

        return searchableText.contains(normalizedSearchQuery)
    }

    private fun getActivityLogTranslation(
        log: PanelActivityLog,
        translations: Map<String, String>
    ): String {
        val type = log.type ?: return ""
        val details = log.details.map
        val globalKey = "activity-logs.$type"
        val globalTemplate = translations[globalKey]

        if (globalTemplate != null) {
            return replaceTranslationVariables(globalTemplate, details)
        }

        val pluginId = log.pluginId
        if (!pluginId.isNullOrBlank()) {
            val pluginKey = "plugins.$pluginId.activity-logs.$type"
            val pluginTemplate = translations[pluginKey]

            if (pluginTemplate != null) {
                return replaceTranslationVariables(pluginTemplate, details)
            }
        }

        return globalKey
    }

    private fun replaceTranslationVariables(
        template: String,
        variables: Map<String, Any?>
    ): String = PLACEHOLDER_REGEX.replace(template) { matchResult ->
        val key = matchResult.groupValues[1]
        variables[key]?.toString() ?: matchResult.value
    }

    private fun normalizeSearchText(
        value: String,
        localeCode: String
    ): String {
        val locale = Locale.forLanguageTag(localeCode).takeIf { it.language.isNotBlank() } ?: Locale.ROOT
        val noHtml = HTML_REGEX.replace(value, "")
        return noHtml.lowercase(locale)
    }

    companion object {
        private const val PAGE_SIZE = 10L
        private val PLACEHOLDER_REGEX = Regex("\\{([^{}]+)}")
        private val HTML_REGEX = Regex("<[^>]*>?")
    }
}