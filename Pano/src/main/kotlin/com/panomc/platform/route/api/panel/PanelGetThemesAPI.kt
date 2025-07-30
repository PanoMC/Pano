package com.panomc.platform.route.api.panel

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.ResourceHashStatus
import com.panomc.platform.util.ResourceStatusType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema

@Endpoint
class PanelGetThemesAPI(
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema().items(enumSchema(*ResourceStatusType.entries.map { it.name }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val statusType = ResourceStatusType.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: ResourceStatusType.ALL.name
        )

        val config = configManager.config
        val currentTheme = config.currentTheme

        val themes = when (statusType) {
            ResourceStatusType.ACTIVE -> uiManager.installedThemeList.filter { it.id == currentTheme }
            ResourceStatusType.DISABLED -> uiManager.installedThemeList.filter { it.id != currentTheme }
            else -> uiManager.installedThemeList
        }

        val hashList = themes.map { it.hash }

        val sqlClient = getSqlClient()

        val resourceHashes = databaseManager.resourceHashDao.byListOfHash(hashList, sqlClient)

        val result = mutableMapOf(
            "data" to themes.map { theme ->
                mapOf(
                    "id" to theme.id,
                    "version" to theme.version,
                    "author" to theme.author,
                    "active" to (currentTheme == theme.id),
                    "panoVersion" to theme.panoVersion,
                    "license" to theme.license,
                    "hash" to theme.hash,
                    "createdAt" to theme.createdAt,
                    "updatedAt" to theme.updatedAt,
                    "installedBy" to theme.installedBy,
                    "verifyStatus" to if (resourceHashes[theme.hash] == null) ResourceHashStatus.UNKNOWN else resourceHashes[theme.hash]!!.status,
                    "sourceUrl" to theme.sourceUrl
                )
            }
        )

        return Successful(result)
    }
}