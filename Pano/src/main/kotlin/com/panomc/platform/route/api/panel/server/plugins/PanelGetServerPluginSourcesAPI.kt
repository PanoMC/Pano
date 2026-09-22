package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.server.plugins.PluginSourceCatalog
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Which plugin directories this server can be searched in.
 *
 * A disabled source comes back with a reason rather than being left out, so the panel can say
 * "CurseForge needs an API key" instead of quietly offering two options where the documentation
 * promises three.
 */
@Endpoint
class PanelGetServerPluginSourcesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginSourceCatalog: PluginSourceCatalog,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/plugins/sources", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val sources = pluginSourceCatalog.sources(server.type)

        return Successful(
            mapOf(
                "sources" to JsonArray(sources.map { it.toJsonObject() }),
                // Install needs a side that can write the jar (§2.4.17: a node, or a plugin with
                // `plugin-install`); search does not. The panel uses this to decide whether an
                // install button belongs on the page at all.
                "installable" to (serverFeatureResolver.resolve(server).plugins.install != null),
                "targetDir" to PluginLoaderMapping.targetDir(server.type)
            )
        )
    }
}
