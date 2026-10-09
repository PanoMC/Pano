package com.panomc.platform.route.api.plugins

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
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
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `GET /api/v1/plugins/:pluginId/_/translations/:locale` -- one plugin's texts in one locale (doc 04
 * §8): the `plugins.<id>` subtree [com.panomc.platform.route.api.GetTranslationsAPI] builds, with
 * the admin's edits merged in the same way, but for one plugin and without the panel's token.
 *
 * 404 for a locale Pano does not know and for a plugin that is unknown or not started.
 */
@Endpoint
class GetPluginTranslationsAPI(
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager
) : Api() {
    override val paths = listOf(Path("/plugins/:pluginId/_/translations/:locale", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "One plugin's texts in one locale, with the admin's edits applied.",
        tag = "plugins",
        response = objectSchema()
            .requiredProperty("locale", stringSchema())
            .requiredProperty("translations", objectSchema()),
        errors = listOf(NotFound::class)
    )

    // A front-end loads these while it renders its maintenance page too.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .pathParameter(param("locale", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val pluginId = parameters.pathParameter("pluginId").string
        val code = parameters.pathParameter("locale").string

        val sqlClient = getSqlClient()

        val wrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        val localeId = databaseManager.localeDao.getIdByCode(code, sqlClient)

        requireServable(wrapper?.pluginState, localeId != null)

        if (wrapper == null || localeId == null) {
            throw NotFound()
        }

        val overrides = databaseManager.translationDao
            .getByLocaleIdAndType(localeId, TranslationType.PLUGIN, sqlClient)
            .associate { it.key to it.value }

        val fromDev = if (configManager.config.developmentMode) {
            PluginDevUtil.getPluginResourceDir(pluginId, "locales")
                ?.let { PluginDevUtil.getPluginLocalesFromDir(it) }
        } else null

        val locales = fromDev ?: wrapper.pluginLocales

        return Successful(response(pluginId, code, locales, overrides))
    }

    companion object {
        /** 404 for a plugin that is unknown (null state) or not started, and for an unknown locale. */
        fun requireServable(pluginState: PluginState?, localeKnown: Boolean) {
            if (pluginState != PluginState.STARTED || !localeKnown) {
                throw NotFound()
            }
        }

        /**
         * The response body. The plugin's own text for [code], else for the default locale; every
         * leaf the admin edited is replaced by the edit, which is stored under the flat
         * `plugins.<pluginId>.<key>` name. An edit for a key the plugin does not have is ignored,
         * exactly as in the all-plugins endpoint.
         */
        fun response(
            pluginId: String,
            code: String,
            locales: Map<String, JsonObject>,
            overrides: Map<String, String>
        ): Map<String, Any?> {
            val base = locales[code] ?: locales[AppConstants.DEFAULT_LOCALE_CODE]

            val merged = if (base == null) {
                JsonObject()
            } else {
                val flat = JsonObjectUtil.flattenJsonObject(base).map { (key, value) ->
                    key to (overrides["plugins.$pluginId.$key"] ?: value)
                }.toMap()

                JsonObjectUtil.unflattenToJsonObject(flat)
            }

            return mapOf("locale" to code, "translations" to merged)
        }
    }
}
