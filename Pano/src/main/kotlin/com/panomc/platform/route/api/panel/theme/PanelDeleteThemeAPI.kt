package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.AppConstants
import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.UIManager
import com.panomc.platform.UIManager.Companion.InstalledBy
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.Unauthorized
import com.panomc.platform.model.*
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.springframework.context.annotation.Lazy
import java.io.File

@Endpoint
class PanelDeleteThemeAPI(
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    @param:Lazy private val router: Router
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()

        if (theme.installedBy == InstalledBy.SYSTEM) {
            throw Unauthorized()
        }

        val themeFolder = File(THEMES_FOLDER_PATH, theme.id)

        val config = configManager.config
        val currentTheme = config.currentTheme

        if (theme.id == currentTheme) {
            config.currentTheme = AppConstants.DEFAULT_THEME_ID
            configManager.saveConfig()
        }

        if (uiManager.activeTheme == theme.id) {
            uiManager.stopUI(theme.id)
            uiManager.disableUIOnRoute(router, Type.THEME_UI)

            if (config.initUi) {
                uiManager.startUI(AppConstants.DEFAULT_THEME_ID)
            }

            uiManager.activateThemeUI(router, AppConstants.DEFAULT_THEME_ID)
        }

        if (themeFolder.exists()) {
            themeFolder.deleteRecursively()
        }

        val sqlClient = getSqlClient()

        databaseManager.resourceHashDao.deleteByHash(theme.hash, sqlClient)

        uiManager.reloadInstalledThemes()

        return Successful()
    }
}