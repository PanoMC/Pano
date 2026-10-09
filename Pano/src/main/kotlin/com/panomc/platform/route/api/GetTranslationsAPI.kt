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
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema

@Endpoint
class GetTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager
) : Api() {
    private val logger = LoggerFactory.getLogger(GetTranslationsAPI::class.java)

    companion object {
        /**
         * One plugin's flat texts with the admin's edits applied (doc 03 section 5.2). The database keeps an
         * edit under the legacy key `plugins.<pluginId>.<key>`. Returns the merged texts and, sorted, the keys
         * whose value came from an edit, so a client that layers a theme file in between can tell them apart.
         */
        internal fun applyAdminEdits(
            pluginId: String,
            flat: Map<String, Any>,
            edits: Map<String, String>
        ): Pair<Map<String, Any>, List<String>> {
            val edited = mutableListOf<String>()
            val merged = flat.mapValues { (key, value) ->
                val custom = edits["plugins.$pluginId.$key"]

                if (custom != null) {
                    edited.add(key)

                    custom
                } else {
                    value
                }
            }

            return merged to edited.sorted()
        }
    }

    override val paths = listOf(Path("/locales/:code/translations/types/:type", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The texts of one locale and type, with the admin's edits applied.",
        tag = "locales",
        response = objectSchema()
            .requiredProperty("data", objectSchema())
            .requiredProperty(
                "meta",
                objectSchema()
                    .requiredProperty("totalCount", intSchema())
                    .requiredProperty("pluginAdminKeys", objectSchema())
            ),
        errors = listOf(NotFound::class)
    )

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
        val pluginAdminKeys = sortedMapOf<String, List<String>>()

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

                    // DB overrides are still stored under the legacy flat "plugins.{id}.{key}"
                    // key; look them up by that string but never split it into the structure.
                    val (pluginFlatTranslations, editedKeys) = applyAdminEdits(
                        wrapper.pluginId,
                        JsonObjectUtil.flattenJsonObject(pluginTranslations),
                        customPluginTranslations
                    )

                    if (editedKeys.isNotEmpty()) {
                        pluginAdminKeys[wrapper.pluginId] = editedKeys
                    }

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
                    // plugin id -> flat keys whose text is an admin edit (a client layering a theme's plugin
                    // texts puts these above them and everything else below)
                    "pluginAdminKeys" to pluginAdminKeys,
                )
            )
        )
    }
}