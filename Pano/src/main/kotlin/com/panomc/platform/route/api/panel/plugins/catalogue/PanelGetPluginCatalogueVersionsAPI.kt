package com.panomc.platform.route.api.panel.plugins.catalogue

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.error.InvalidData
import com.panomc.platform.model.*
import com.panomc.platform.server.plugins.PluginProjectType
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.plugins.PluginSourceId
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Every published version of one catalogue project, with no server to judge it against.
 *
 * `compatible` is reported true throughout for the reason the catalogue search reports it: there
 * is no server yet, so nothing constrains the choice. The wizard uses this to let somebody pick
 * which build of a modpack to install.
 */
@Endpoint
class PanelGetPluginCatalogueVersionsAPI(
    private val authProvider: AuthProvider,
    private val pluginSourceCatalog: PluginSourceCatalog
) : PanelApi() {
    // Same reason as the catalogue search: `/api/panel/plugins/:pluginId` must not win this path.
    override val order = 0

    override val paths = listOf(Path("/api/panel/plugins/search/:source/:projectId/versions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("source", stringSchema()))
            .pathParameter(param("projectId", stringSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requireAnyPermission(context, CreateServersPermission(), ManageServersPermission())

        val parameters = getParameters(context)

        val source = PluginSourceId.fromId(parameters.pathParameter("source").string) ?: throw InvalidData()

        val projectType =
            PluginProjectType.fromId(parameters.queryParameter("type")?.string ?: PluginProjectType.PLUGIN.id)
                ?: throw InvalidData()

        val projectId = parameters.pathParameter("projectId").string

        if (projectId.isBlank() || projectId.length > MAX_PROJECT_ID_LENGTH) {
            throw InvalidData()
        }

        val versions = pluginSourceCatalog.catalogueVersions(
            source = source,
            projectType = projectType,
            projectId = projectId
        )

        return Successful(
            mapOf(
                "source" to source.id,
                "type" to projectType.id,
                "projectId" to projectId,
                "versions" to JsonArray(versions.map { it.toJsonObject() })
            )
        )
    }

    companion object {
        private const val MAX_PROJECT_ID_LENGTH = 128
    }
}
