package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.NodeJarProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeUpdateAvailability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.plugins.PanoPluginJarProvider
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything on the Updates page that is not Pano itself (`GET /api/panel/updates/servers`).
 *
 * One shape for every row: `{ id, name, kind, current, latest, updateAvailable, online }`, with
 * `kind` one of `node` (the pano-node daemon on a host, updated with
 * `POST /api/panel/nodes/:id/update`), `agent` (a pano-node dedicated to one adopted server, listed
 * under that server's name with its `serverId` and updated with
 * `POST /api/panel/servers/:serverId/agent/update`) or `pano-plugin` (the plugin inside one server,
 * updated with `POST /api/panel/servers/:id/pano-plugin/update`). A plugin row also says which route
 * its update would take right now (`mode`) or, when it has none, why not (`reason`), so the page can
 * disable a button with a sentence instead of letting it fail.
 *
 * `updateAvailable` is a plain boolean here and only ever a definite yes: a development build, a
 * server that never reported a version or a release that could not be looked up are all "no", which
 * is what keeps "update all" and this list agreeing about what an update is.
 *
 * Each kind is filtered by the permission its update asks for — nodes by the nodes permission,
 * agents by the servers permission, plugins one server at a time by the server plugins permission —
 * so a user who looks after two servers sees two rows and no hosts.
 *
 * The newest plugin release is looked up if nobody has yet, but only for a moment: an unreachable
 * GitHub turns into a missing `latest`, never into a page that hangs.
 */
@Endpoint
class PanelGetServerUpdatesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val nodeJarProvider: NodeJarProvider,
    private val serverManager: ServerManager,
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val panoPluginJarProvider: PanoPluginJarProvider,
    private val panoPluginUpdateService: PanoPluginUpdateService,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/servers", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        val nodes = JsonArray()

        val canManageNodes = authProvider.hasPermission(ManageNodesPermission(), context)
        // An agent is updated through its server (`POST /api/panel/servers/:id/agent/update`), under
        // the permission that endpoint asks for rather than the nodes one.
        val canManageServers = authProvider.hasPermission(ManageServersPermission(), context)

        if (canManageNodes || canManageServers) {
            // Hashed once for the whole list: the daemon Pano serves is the same for every node.
            val servedSha256 = nodeJarProvider.locate()?.let { nodeJarProvider.sha256(it) }

            databaseManager.nodeDao.getAll(sqlClient)
                .filter { it.approved }
                .filter { if (it.agent) canManageServers else canManageNodes }
                .forEach { node ->
                    val row = JsonObject()
                        .put("id", node.id)
                        .put("name", node.name)
                        .put("kind", if (node.agent) KIND_AGENT else KIND_NODE)
                        .put("current", node.version)
                        .put("latest", Main.VERSION)
                        .put(
                            "updateAvailable",
                            NodeUpdateAvailability.isAvailable(
                                nodeVersion = node.version,
                                platformVersion = Main.VERSION,
                                nodeJarSha256 = nodeManager.getJarSha256(node.id),
                                servedSha256 = servedSha256
                            )
                        )
                        .put("online", nodeManager.isConnected(node.id))

                    // A Pano Agent is never shown as a node: the panel knows it only as the server
                    // it adopted, so the row is named after that server and says which one it is.
                    if (node.agent) {
                        databaseManager.serverDao.getAllByNodeId(node.id, sqlClient).firstOrNull()?.let { server ->
                            row.put("serverId", server.id)
                            row.put("name", server.customName ?: server.name)
                        }
                    }

                    nodes.add(row)
                }
        }

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)
            .filter { ManagedPluginJarResolver.platformOf(it.type) != null }
            .filter { authProvider.hasPermission(ManageServerPluginsPermission(), context, it.id) }

        val latestByType = mutableMapOf<ServerType, String?>()

        servers.map { it.type }.distinct().forEach { type ->
            latestByType[type] = withTimeoutOrNull(LOOKUP_BUDGET_MS) { panoPluginJarProvider.latestVersion(type) }
                ?: managedPluginJarResolver.latestVersionOrWarm(type)
        }

        val pluginRows = JsonArray()

        servers.forEach { server ->
            val latest = latestByType[server.type]
            val mode = panoPluginUpdateService.modeFor(server)
            val pluginConnected = serverManager.isConnected(server.id)
            val reason = if (mode != null) null else PanoPluginUpdatePlan.refusalFor(
                type = server.type,
                managed = server.isManaged,
                pluginConnected = pluginConnected
            )

            pluginRows.add(
                JsonObject()
                    .put("id", server.id)
                    .put("name", server.customName ?: server.name)
                    .put("kind", KIND_PANO_PLUGIN)
                    .put("current", server.pluginVersion)
                    .put("latest", latest)
                    .put("updateAvailable", PanoPluginUpdatePlan.needsUpdate(server.pluginVersion, latest))
                    .put("online", pluginConnected)
                    .put("serverType", server.type.name)
                    .put("managed", server.isManaged)
                    .put("mode", mode?.wire)
                    .put("reason", reason)
                    // No route, but the admin can put the served jar in place by hand.
                    .put("manual", reason != null && PanoPluginUpdatePlan.canUpdateByHand(reason, server.isManaged))
            )
        }

        return Successful(
            mapOf(
                "nodes" to nodes,
                "servers" to pluginRows,
                "platformVersion" to Main.VERSION,
                // Whether nodes and agents are updated on their own (`managed-servers.node-auto-update`);
                // switched with `PUT /api/panel/updates/node-auto-update`.
                "nodeAutoUpdate" to configManager.config.effectiveManagedServers.nodeAutoUpdate
            )
        )
    }

    companion object {
        const val KIND_NODE = "node"

        /** A pano-node in agent mode, dedicated to one adopted server (AGENT.md A2). */
        const val KIND_AGENT = "agent"
        const val KIND_PANO_PLUGIN = "pano-plugin"

        /** How long the page waits for a first release lookup before showing no `latest`. */
        private const val LOOKUP_BUDGET_MS = 3_000L
    }
}
