package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.response.GetServerSettingsEventResponse
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
                        .optionalProperty(
                            "settings", objectSchema()
                                .requiredProperty("authIntegration", booleanSchema())
                                .requiredProperty("authRequireVerified", booleanSchema())
                                .requiredProperty("authKickAfterRegister", booleanSchema())
                                .requiredProperty("banIntegration", booleanSchema())
                                .requiredProperty("permissionIntegration", booleanSchema())
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

            serverSettings.authIntegration = settings.getBoolean("authIntegration")
            serverSettings.authRequireVerified = settings.getBoolean("authRequireVerified")
            serverSettings.authKickAfterRegister = settings.getBoolean("authKickAfterRegister")
            serverSettings.banIntegration = settings.getBoolean("banIntegration")
            serverSettings.permissionIntegration = settings.getBoolean("permissionIntegration")

            databaseManager.serverDao.updateSettingsById(serverSettings, id, sqlClient)

            val foundServer = serverManager.connectedServers.keys.find { it.id == id }

            if (foundServer != null) {
                foundServer.settings = serverSettings

                val platformLocale = configManager.config.locale
                val translationsByLocale = i18nManager.getTranslationsByLocale(TranslationType.MC_PLUGIN)

                val response = GetServerSettingsEventResponse(
                    serverSettings.authIntegration,
                    serverSettings.authRequireVerified,
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
}