package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.plugins.PluginProjectType
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.plugins.PluginSourceId
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Searches one plugin directory for things this server could run.
 *
 * Works for linked servers as well as managed ones: browsing what exists is useful even when Pano
 * has no way to install it, and the install endpoint is where the managed-only line is drawn.
 */
@Endpoint
class PanelSearchServerPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginSourceCatalog: PluginSourceCatalog
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins/search", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("source", stringSchema()))
            .queryParameter(optionalParam("q", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("limit", numberSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val source = PluginSourceId.fromId(parameters.queryParameter("source")?.string ?: PluginSourceId.MODRINTH.id)
            ?: throw InvalidData()

        // An explicit kind overrides what the software implies, so the one search endpoint can
        // also be asked for modpacks or for mods on a server whose loader says otherwise.
        val projectType = parameters.queryParameter("type")?.string?.let {
            PluginProjectType.fromId(it) ?: throw InvalidData()
        }

        val query = parameters.queryParameter("q")?.string.orEmpty()
        val page = parameters.queryParameter("page")?.integer ?: 0
        val limit = parameters.queryParameter("limit")?.integer ?: DEFAULT_LIMIT

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val results = pluginSourceCatalog.search(
            source = source,
            type = server.type,
            softwareVersion = server.softwareVersion ?: server.version,
            query = query,
            page = page,
            limit = limit,
            projectType = projectType
        )

        return Successful(results.toJsonObject().put("source", source.id).map)
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
    }
}
