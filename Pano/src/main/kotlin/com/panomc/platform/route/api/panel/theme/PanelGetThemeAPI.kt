package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.ResourceHashStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetThemeAPI(
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes/:themeId", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("themeId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val themeId = parameters.pathParameter("themeId").string

        val theme = uiManager.installedThemeList.find { it.id == themeId } ?: throw NotFound()

        val config = configManager.config
        val currentTheme = config.currentTheme

        val sqlClient = getSqlClient()

        val resourceHashes = databaseManager.resourceHashDao.byListOfHash(listOf(theme.hash), sqlClient)

        return Successful(
            mapOf(
                "data" to mapOf(
                    "id" to theme.id,
                    "title" to theme.title,
                    "description" to theme.description,
                    "version" to theme.version,
                    "author" to theme.author,
                    "active" to (currentTheme == theme.id),
                    "panoVersion" to theme.panoVersion,
                    "screenshots" to theme.screenshots,
                    "license" to theme.license,
                    "hash" to theme.hash,
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