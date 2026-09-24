package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginUpdatedLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PluginUpdateService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * Updates every tracked jar on a server that has something newer.
 *
 * Reports both halves. A plugin that could not be updated is listed with the reason rather than
 * silently left behind, because the one thing worse than "three of your five plugins updated" is
 * an admin believing all five did.
 *
 * Every update is its own node task, so the page follows them exactly as it already follows a
 * single install, and one plugin whose source is down does not hold up the other four.
 */
@Endpoint
class PanelUpdateAllServerPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val pluginService: ManagedServerPluginService,
    private val pluginUpdateService: PluginUpdateService,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins/update-all", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.PLUGIN_INSTALL, userId, id)) {
            throw RateLimited()
        }

        val sqlClient = getSqlClient()

        val target = fileClient.resolve(id, sqlClient, ServerFeature.PLUGINS_INSTALL)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        val rows = databaseManager.serverPluginInstallDao.getByServerId(id, sqlClient)

        val started = JsonArray()
        val skipped = JsonArray()

        rows.forEach { row ->
            val attempt = try {
                pluginUpdateService.plan(target.server, row)
            } catch (_: Exception) {
                PluginUpdateService.UpdateAttempt.Refused(REASON_FAILED)
            }

            if (attempt is PluginUpdateService.UpdateAttempt.Refused) {
                skipped.add(JsonObject().put("filename", row.filename).put("reason", attempt.reason))

                return@forEach
            }

            val plan = (attempt as PluginUpdateService.UpdateAttempt.Ready).plan

            val task = try {
                pluginService.install(
                    target = target,
                    source = plan.source,
                    projectId = row.projectId,
                    version = plan.version,
                    file = plan.file,
                    filename = plan.filename,
                    replaceFilename = row.filename,
                    createdBy = userId,
                    sqlClient = sqlClient
                )
            } catch (_: Exception) {
                // A node that went away mid-run is reported per plugin rather than as a failed
                // request: the ones already started are real and the panel has to know about them.
                skipped.add(JsonObject().put("filename", row.filename).put("reason", REASON_FAILED))

                return@forEach
            }

            started.add(
                JsonObject()
                    .put("filename", plan.filename)
                    .put("taskId", task.id)
                    .put("taskUuid", task.uuid)
            )

            databaseManager.panelActivityLogDao.add(
                ServerPluginUpdatedLog(
                    userId = userId,
                    username = username,
                    serverId = id,
                    serverName = target.server.customName ?: target.server.name,
                    pluginName = row.projectName ?: row.filename,
                    fromVersion = row.versionNumber,
                    toVersion = plan.version.versionNumber ?: plan.version.name
                ),
                sqlClient
            )
        }

        return Successful(mapOf("started" to started, "skipped" to skipped))
    }

    companion object {
        /** The node refused the push, or the source answered with something unusable. */
        const val REASON_FAILED = "FAILED"
    }
}
