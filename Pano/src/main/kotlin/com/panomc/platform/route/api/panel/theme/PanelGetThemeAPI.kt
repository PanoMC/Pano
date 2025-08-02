package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.FileUtil.getSize
import com.panomc.platform.util.ResourceHashStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class PanelGetThemeAPI(
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()
        val themeFolder = File(THEMES_FOLDER_PATH, theme.id)

        val activeTheme = uiManager.activeTheme

        val sqlClient = getSqlClient()

        val resourceHashes = databaseManager.resourceHashDao.byListOfHash(listOf(theme.hash), sqlClient)

        return Successful(
            mapOf(
                "data" to mapOf(
                    "id" to theme.id,
                    "title" to theme.title,
                    "description" to theme.description,
                    "panoVersion" to theme.panoVersion,
                    "version" to theme.version,
                    "author" to theme.author,
                    "active" to (activeTheme == theme.id),
                    "panoVersion" to theme.panoVersion,
                    "screenshots" to theme.screenshots,
                    "license" to theme.license,
                    "hash" to theme.hash,
                    "size" to themeFolder.getSize(),
                    "createdAt" to theme.createdAt,
                    "updatedAt" to theme.updatedAt,
                    "installedBy" to theme.installedBy,
                    "verifyStatus" to if (resourceHashes[theme.hash] == null) ResourceHashStatus.UNKNOWN else resourceHashes[theme.hash]!!.status,
                    "sourceUrl" to theme.sourceUrl
                )
            )
        )
    }
}