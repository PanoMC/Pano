package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ActivatedThemeLog
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.ThemeLicenseRequired
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.model.*
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.springframework.context.annotation.Lazy

@Endpoint
class PanelActivateThemeAPI(
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    @param:Lazy private val router: Router
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()

        val config = configManager.config

        if (theme.id == uiManager.activeTheme) {
            return Successful()
        }

        // Premium theme gate: stop the previous theme only after we've successfully fetched +
        // verified the license. Otherwise an operator clicking "Activate" on a premium theme
        // they don't own would knock the site offline (vanilla stopped, premium refused).
        val previousActiveTheme = uiManager.activeTheme
        uiManager.stopUI(previousActiveTheme)
        uiManager.disableUIOnRoute(router, Type.THEME_UI)

        try {
            uiManager.startUI(theme.id)
        } catch (e: LicenseRequiredException) {
            // Bring the previous theme back online so the public site keeps working.
            try {
                uiManager.startUI(previousActiveTheme)
                uiManager.activateThemeUI(router, previousActiveTheme)
            } catch (_: Throwable) {
                // Best-effort: if the previous theme was itself premium-and-unlicensed (rare),
                // the renewal sweep / next request flow will recover via the default-theme fallback.
            }
            throw ThemeLicenseRequired(
                extras = mapOf(
                    "themeId" to theme.id,
                    "licenseDeniedReason" to e.reason.publicId,
                    "message" to e.message,
                )
            )
        }

        config.currentTheme = theme.id
        configManager.saveConfig()

        uiManager.activateThemeUI(router, theme.id)

        val sqlClient = getSqlClient()

        databaseManager.resourceHashDao.deleteByHash(theme.hash, sqlClient)

        uiManager.reloadInstalledThemes()

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            ActivatedThemeLog(
                userId,
                username,
                theme.id,
            ), sqlClient
        )

        return Successful()
    }
}