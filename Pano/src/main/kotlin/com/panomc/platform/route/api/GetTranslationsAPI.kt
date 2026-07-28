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
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState
import org.slf4j.LoggerFactory

@Endpoint
class GetTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager
) : Api() {
    private val logger = LoggerFactory.getLogger(GetTranslationsAPI::class.java)

    override val paths = listOf(Path("/api/locales/:code/translations/types/:type", RouteType.GET))

    // panel-ui bootstraps its i18n from here, both SSR and CSR.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

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

        // Build the per-plugin translations under a dedicated "plugins" object keyed by the
        // literal pluginId. Nesting the pluginId as a real map key (instead of joining it into a
        // dotted flat key) keeps it from being split on '.' when a pluginId — or a translation
        // key — itself contains a dot, which previously produced wrong/cross-plugin translations.
        val pluginsObject = JsonObject()
        var pluginTranslationCount = 0

        pluginManager.getPluginWrappers()
            .filter { it.pluginState == PluginState.STARTED }
            .forEach { wrapper ->
                try {
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
                        ?: return@forEach

                    val pluginFlatTranslations = JsonObjectUtil.flattenJsonObject(pluginTranslations)
                        .map { (key, value) ->
                            // DB overrides are still stored under the legacy flat "plugins.{id}.{key}"
                            // key; look them up by that string but never split it into the structure.
                            val overrideKey = "plugins.${wrapper.pluginId}.$key"

                            key to (customPluginTranslations[overrideKey] ?: value)
                        }
                        .toMap()

                    pluginsObject.put(
                        wrapper.pluginId,
                        JsonObjectUtil.unflattenToJsonObject(pluginFlatTranslations)
                    )
                    pluginTranslationCount += pluginFlatTranslations.size
                } catch (e: Exception) {
                    // One plugin's broken locales must not abort the whole merge.
                    logger.error("Failed to load translations for plugin ${wrapper.pluginId}", e)
                }
            }

        val data = JsonObjectUtil.unflattenToJsonObject(translations)

        if (!pluginsObject.isEmpty) {
            data.put("plugins", pluginsObject)
        }

        return Successful(
            mutableMapOf(
                "data" to data,
                "meta" to mapOf(
                    "totalCount" to (translations.count() + pluginTranslationCount),
                )
            )
        )
    }
}