package com.panomc.platform.route.api

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.JsonObjectUtil
import com.panomc.platform.util.PluginDevUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState

@Endpoint
class GetTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager
) : Api() {
    override val paths = listOf(Path("/api/locales/:code/translations/types/:type", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("code", stringSchema()))
            .pathParameter(param("type", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val code = parameters.pathParameter("code").string
        val type = try {
            TranslationType.valueOf(parameters.pathParameter("type").string)
        } catch (_: Exception) {
            throw BadRequest("Invalid type")
        }

        val sqlClient = getSqlClient()

        if (type == TranslationType.PANEL && (!authProvider.isLoggedIn(context) || !authProvider.hasAccessPanel(context))) {
            throw NoPermission()
        }

        if (type == TranslationType.PLUGIN) {
            throw NoPermission()
        }

        if (!databaseManager.localeDao.existsByCode(code, sqlClient)) {
            throw NotFound()
        }

        var localeId = databaseManager.localeDao.getIdByCode(code, sqlClient)

        if (localeId == null) {
            localeId = databaseManager.localeDao.getIdByCode(AppConstants.DEFAULT_LOCALE_CODE, sqlClient)!!
        }

        val customTranslations = databaseManager.translationDao.getByLocaleIdAndType(localeId, type, sqlClient)
        val customPluginTranslationsInDb =
            databaseManager.translationDao.getByLocaleIdAndType(localeId, TranslationType.PLUGIN, sqlClient)
        val customPluginTranslations = customPluginTranslationsInDb.associate { it.key to it.value }
        val translations = mutableMapOf<String, Any>()

        translations.putAll(customTranslations.associate { it.key to it.value })

        translations.putAll(
            pluginManager.getPluginWrappers()
                .filter { it.pluginState == PluginState.STARTED }
                .mapNotNull { wrapper ->
                    val pluginTranslationsFromDev = if (configManager.config.developmentMode) {
                        val localesDir = PluginDevUtil.getPluginResourceDir(wrapper.pluginId, "locales")
                        if (localesDir != null) {
                            val locales = PluginDevUtil.getPluginLocalesFromDir(localesDir)
                            locales[code] ?: locales[AppConstants.DEFAULT_LOCALE_CODE]
                        } else null
                    } else null

                    val pluginTranslations = pluginTranslationsFromDev
                        ?: wrapper.pluginLocales[code]
                        ?: wrapper.pluginLocales[AppConstants.DEFAULT_LOCALE_CODE]

                    if (pluginTranslations == null) return@mapNotNull null

                    JsonObjectUtil.flattenJsonObject(pluginTranslations)
                        .map { (key, value) ->
                            val newKey = "plugins.${wrapper.pluginId}.$key"

                            newKey to (customPluginTranslations[newKey] ?: value)
                        }
                }
                .flatten()
                .toMap()
        )

        return Successful(
            mutableMapOf(
                "data" to JsonObjectUtil.unflattenToJsonObject(translations),
                "meta" to mapOf(
                    "totalCount" to translations.count(),
                )
            )
        )
    }
}