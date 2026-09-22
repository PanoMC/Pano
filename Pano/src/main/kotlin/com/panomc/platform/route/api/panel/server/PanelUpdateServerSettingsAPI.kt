package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.response.GetServerSettingsEventResponse
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateServerSettingsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager,
    private val i18nManager: I18nManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/settings", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        // Every field optional: the game-integration page sends its five switches,
                        // the preferences page sends `autoUpdateCheck` alone (SM-69), and whatever
                        // a request leaves out keeps its stored value -- so neither page can
                        // overwrite the other's switches with a stale copy.
                        .optionalProperty(
                            "settings", objectSchema()
                                .optionalProperty("authIntegration", booleanSchema())
                                .optionalProperty("authRequireVerified", booleanSchema())
                                .optionalProperty("authKickAfterRegister", booleanSchema())
                                .optionalProperty("banIntegration", booleanSchema())
                                .optionalProperty("permissionIntegration", booleanSchema())
                                .optionalProperty("autoUpdateCheck", booleanSchema())
                        )
                        .optionalProperty("customName", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val settings = data.getJsonObject("settings")
        val customName = data.getString("customName")

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotFound()
        var updated = false

        if (customName != null) {
            if (customName.isBlank() || customName.length > 64) {
                throw BadRequest()
            }

            server.customName = customName

            updated = true
        }

        if (updated) {
            databaseManager.serverDao.update(
                server,
                sqlClient
            )
        }

        if (settings != null) {
            val serverSettings = server.settings

            applySettings(serverSettings, settings)

            databaseManager.serverDao.updateSettingsById(serverSettings, id, sqlClient)

            val foundServer = serverManager.connectedServers.keys.find { it.id == id }

            if (foundServer != null) {
                // Kept in step even when nothing the plugin reads changed: the connected entity is
                // what the next save starts from.
                foundServer.settings = serverSettings
            }

            // The plugin is only told about the switches it acts on; a request that changed
            // nothing but `autoUpdateCheck` is none of its business.
            if (foundServer != null && PLUGIN_SETTINGS.any { settings.containsKey(it) }) {
                val platformLocale = configManager.config.locale
                val translationsByLocale = i18nManager.getTranslationsByLocale(TranslationType.MC_PLUGIN)

                val authConfig = configManager.config.auth

                val response = GetServerSettingsEventResponse(
                    serverSettings.authIntegration,
                    if (!authConfig.requireEmailVerification) false else serverSettings.authRequireVerified,
                    serverSettings.authKickAfterRegister,
                    serverSettings.banIntegration,
                    serverSettings.permissionIntegration,
                    translationsByLocale,
                    platformLocale,
                )

                serverManager.sendMessage(response, foundServer)
            }
        }

        return Successful()
    }

    companion object {
        /** The fields the Minecraft plugin acts on, which it is re-sent whenever one of them is. */
        val PLUGIN_SETTINGS = listOf(
            "authIntegration",
            "authRequireVerified",
            "authKickAfterRegister",
            "banIntegration",
            "permissionIntegration"
        )

        /**
         * Copies a `settings` body onto the stored settings.
         *
         * A field the body does not carry keeps its stored value: an older panel that never heard
         * of `autoUpdateCheck` sends the five integration switches and must not reset the sixth.
         */
        fun applySettings(target: Server.Companion.ServerSettings, body: JsonObject) {
            target.authIntegration = body.getBoolean("authIntegration", target.authIntegration)
            target.authRequireVerified = body.getBoolean("authRequireVerified", target.authRequireVerified)
            target.authKickAfterRegister = body.getBoolean("authKickAfterRegister", target.authKickAfterRegister)
            target.banIntegration = body.getBoolean("banIntegration", target.banIntegration)
            target.permissionIntegration = body.getBoolean("permissionIntegration", target.permissionIntegration)
            target.autoUpdateCheck = body.getBoolean("autoUpdateCheck", target.autoUpdateCheck)
        }
    }
}
