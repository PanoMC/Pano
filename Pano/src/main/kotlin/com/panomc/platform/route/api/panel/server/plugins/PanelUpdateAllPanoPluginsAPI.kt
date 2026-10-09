package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.ReleaseStage
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan
import com.panomc.platform.server.plugins.PanoPluginJarProvider
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * Updates the Pano plugin on every server that has a newer one waiting
 * (`POST /api/v1/panel/servers/pano-plugin/update-all`).
 *
 * "Every server" means every accepted server whose running version is known and older than the
 * newest build for its software — [PanoPluginUpdatePlan.needsUpdate]'s definite yes, never a
 * maybe — and that this user may manage plugins on. Servers the user has no permission for are
 * left out entirely rather than listed as skipped: naming them would be telling somebody about
 * servers they were not given.
 *
 * Reports both halves, like the per-server "update all plugins": the servers that got a task, with
 * the task and the route it took, and the ones that did not, with the reason. One offline node or
 * one plugin too old to update itself never stops the rest; each server is its own attempt.
 */
@Endpoint
class PanelUpdateAllPanoPluginsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panoPluginUpdateService: PanoPluginUpdateService,
    private val panoPluginJarProvider: PanoPluginJarProvider,
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/pano-plugin/update-all", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)
            .filter { authProvider.hasPermission(ManageServerPluginsPermission(), context, it.id) }

        // Looked up once per software, not once per server: a network of twenty Paper servers asks
        // GitHub (or its cache) one question.
        // Per software and per release channel: a plugin is updated within the channel it was
        // installed from, so two Paper servers on different channels have different "latest"s.
        val latestByKey = mutableMapOf<Pair<ServerType, ReleaseStage>, String?>()

        servers.forEach { server ->
            val key = server.type to managedPluginJarResolver.channelFor(server.pluginVersion)

            if (key !in latestByKey) {
                latestByKey[key] = panoPluginJarProvider.latestVersion(server.type, server.pluginVersion)
            }
        }

        val due = PanoPluginUpdatePlan.serversNeedingUpdate(
            servers.map { PanoPluginUpdatePlan.Candidate(it.id, it.type, it.pluginVersion) }
        ) { candidate ->
            latestByKey[candidate.type to managedPluginJarResolver.channelFor(candidate.installedVersion)]
        }

        val byId = servers.associateBy { it.id }

        val started = JsonArray()
        val skipped = JsonArray()

        due.forEach { candidate ->
            val server = byId[candidate.serverId] ?: return@forEach
            val name = server.customName ?: server.name

            if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.PLUGIN_INSTALL, userId, server.id)) {
                skipped.add(skippedEntry(server.id, name, PanoPluginUpdatePlan.REASON_RATE_LIMITED))

                return@forEach
            }

            val attempt = try {
                panoPluginUpdateService.start(server, userId, sqlClient)
            } catch (_: Exception) {
                PanoPluginUpdateService.Attempt.Refused(PanoPluginUpdatePlan.REASON_SEND_FAILED)
            }

            when (attempt) {
                is PanoPluginUpdateService.Attempt.Started -> started.add(
                    JsonObject()
                        .put("serverId", server.id)
                        .put("name", name)
                        .put("taskId", attempt.task.id)
                        .put("taskUuid", attempt.task.uuid)
                        .put("mode", attempt.mode.wire)
                        .put("fromVersion", attempt.fromVersion)
                        .put("toVersion", attempt.toVersion)
                )

                is PanoPluginUpdateService.Attempt.Refused ->
                    skipped.add(skippedEntry(server.id, name, attempt.reason))
            }
        }

        return Successful(
            mapOf(
                "started" to started,
                "skipped" to skipped,
                "restartRequired" to !started.isEmpty
            )
        )
    }

    private fun skippedEntry(serverId: Long, name: String, reason: String) = JsonObject()
        .put("serverId", serverId)
        .put("name", name)
        .put("reason", reason)
}
