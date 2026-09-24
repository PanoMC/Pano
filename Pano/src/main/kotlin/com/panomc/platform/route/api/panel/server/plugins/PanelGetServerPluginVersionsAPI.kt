package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.plugins.PluginSourceCatalog
import com.panomc.platform.server.plugins.PluginSourceId
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Every published version of one project, each flagged with whether this server can run it.
 *
 * Incompatible versions are listed rather than filtered out: somebody deliberately installing an
 * older build for a server the author has not updated for yet is a legitimate thing to do, and a
 * list that silently drops what the website shows looks broken.
 */
@Endpoint
class PanelGetServerPluginVersionsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val pluginSourceCatalog: PluginSourceCatalog
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths =
        listOf(Path("/api/panel/servers/:id/plugins/search/:source/:projectId/versions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("source", stringSchema()))
            .pathParameter(param("projectId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val source = PluginSourceId.fromId(parameters.pathParameter("source").string) ?: throw InvalidData()
        val projectId = parameters.pathParameter("projectId").string

        if (projectId.isBlank() || projectId.length > MAX_PROJECT_ID_LENGTH) {
            throw InvalidData()
        }

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val versions = pluginSourceCatalog.versions(
            source = source,
            type = server.type,
            softwareVersion = server.softwareVersion ?: server.version,
            projectId = projectId
        )

        return Successful(
            mapOf(
                "source" to source.id,
                "projectId" to projectId,
                "versions" to JsonArray(versions.map { it.toJsonObject() })
            )
        )
    }

    companion object {
        private const val MAX_PROJECT_ID_LENGTH = 128
    }
}
