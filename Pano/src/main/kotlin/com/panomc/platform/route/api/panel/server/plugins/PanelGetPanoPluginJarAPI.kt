package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.model.*
import com.panomc.platform.server.plugins.PanoPluginJarProvider
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.kotlin.coroutines.coAwait
import com.panomc.platform.util.UsageMode

/**
 * The Pano plugin build for one server, as a download for the admin
 * (`GET /api/panel/servers/:id/pano-plugin/jar`).
 *
 * The hand-update path of [PanelUpdatePanoPluginAPI]: a linked server whose plugin is too old to
 * replace itself (or is not connected) is updated by putting this jar in its `plugins/` folder once.
 * It is the same file `GET /api/server/pano-plugin/jar` gives a plugin — the build for the server's
 * platform, which may be a development build that exists nowhere else — under the permission a
 * plugin install needs, since that is what the admin is about to do with it.
 */
@Endpoint
class PanelGetPanoPluginJarAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panoPluginJarProvider: PanoPluginJarProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/pano-plugin/jar", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val id = getParameters(context).pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val server = databaseManager.serverDao.getById(id, getSqlClient()) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val jar = panoPluginJarProvider.prepare(server.type)
            ?: throw ServerCapabilityMissing(
                extras = mapOf(
                    "feature" to PanelUpdatePanoPluginAPI.FEATURE,
                    "reason" to PanoPluginUpdatePlan.REASON_JAR_UNAVAILABLE
                )
            )

        context.response()
            .putHeader("Content-Type", "application/java-archive")
            .putHeader("Content-Length", jar.size.toString())
            .putHeader("Content-Disposition", "attachment; filename=\"${jar.fileName}\"")
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("Cache-Control", "no-store")
            .sendFile(jar.file.absolutePath)
            .coAwait()

        return null
    }
}
