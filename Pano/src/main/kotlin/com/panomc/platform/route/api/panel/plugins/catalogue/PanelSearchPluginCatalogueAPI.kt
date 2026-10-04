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
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * The same directories the plugin browser searches, asked without a server.
 *
 * The create-server wizard needs this: picking a modpack is what *decides* the software and the
 * game version, so there is nothing to derive a loader facet from yet and the caller names the
 * kind of project instead. Nothing is installed from here — that happens through the wizard or
 * through the server-scoped install endpoint — so the gate is the permission to create or manage
 * servers rather than the per-server plugin permission, which has no server to be scoped to.
 */
@Endpoint
class PanelSearchPluginCatalogueAPI(
    private val authProvider: AuthProvider,
    private val pluginSourceCatalog: PluginSourceCatalog
) : PanelApi() {
    // Registered ahead of `/api/panel/plugins/:pluginId`, which would otherwise answer this with
    // a plugin called "search".
    override val order = 0

    override val paths = listOf(Path("/api/panel/plugins/search", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("source", stringSchema()))
            .queryParameter(optionalParam("q", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("limit", numberSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requireAnyPermission(context, CreateServersPermission(), ManageServersPermission())

        val parameters = getParameters(context)

        val source = PluginSourceId.fromId(parameters.queryParameter("source")?.string ?: PluginSourceId.MODRINTH.id)
            ?: throw InvalidData()

        val projectType =
            PluginProjectType.fromId(parameters.queryParameter("type")?.string ?: PluginProjectType.PLUGIN.id)
                ?: throw InvalidData()

        val query = parameters.queryParameter("q")?.string.orEmpty()
        val page = parameters.queryParameter("page")?.integer ?: 0
        val limit = parameters.queryParameter("limit")?.integer ?: DEFAULT_LIMIT

        val results = pluginSourceCatalog.searchCatalogue(
            source = source,
            projectType = projectType,
            query = query,
            page = page,
            limit = limit
        )

        return Successful(
            results.toJsonObject()
                .put("source", source.id)
                .put("type", projectType.id)
                .map
        )
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
    }
}
