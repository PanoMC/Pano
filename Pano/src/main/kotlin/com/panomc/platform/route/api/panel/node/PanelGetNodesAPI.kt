package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.Main
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodeJarProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeUpdateAvailability
import com.panomc.platform.node.NodeUpdateProgressStore
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Lists every node this Pano knows, approved or not.
 *
 * Unapproved nodes are included on purpose: this list is where a pairing request is accepted, so
 * hiding it until it is approved would make approving it impossible.
 *
 * Each node also says whether the daemon it runs is older than the one this Pano would hand it
 * (`updateAvailable`, worked out the same way as the single-node endpoint), because the nodes
 * page is where the update button lives and a badge that needs one request per row is not a
 * badge anybody would ship.
 */
@Endpoint
class PanelGetNodesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val nodeJarProvider: NodeJarProvider,
    private val nodeUpdateProgressStore: NodeUpdateProgressStore
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val sqlClient = getSqlClient()

        // Pano Agents are nodes underneath but never nodes to the admin: each one is shown as the
        // one server it runs, so it is not listed here, not counted, and not offered for new servers.
        val nodes = databaseManager.nodeDao.getAll(sqlClient).filterNot { it.agent }

        // Hashed once for the whole list: the jar Pano serves is the same for every node.
        val servedSha256 = nodeJarProvider.sha256()

        val payload = nodes.map { node ->
            val jarSha256 = nodeManager.getJarSha256(node.id)

            node.toPublicJsonObject()
                .put("connected", nodeManager.isConnected(node.id))
                .put("metrics", nodeManager.getLatestMetrics(node.id)?.toJsonObject())
                .put("serverCount", databaseManager.serverDao.countByNodeId(node.id, sqlClient))
                .put("platformVersion", Main.VERSION)
                .put("jarSha256", jarSha256)
                // `{ version, status, percent, message }` while its daemon updates, else null, and
                // the version an update installs (null for a development build) (SM-77).
                .put("updateProgress", nodeUpdateProgressStore.get(node.id)?.toNodeJsonObject())
                .put("latestVersion", NodeInstallScriptProvider.releaseVersion())
                .put(
                    "updateAvailable",
                    NodeUpdateAvailability.isAvailable(
                        nodeVersion = node.version,
                        platformVersion = Main.VERSION,
                        nodeJarSha256 = jarSha256,
                        servedSha256 = servedSha256
                    )
                )
        }

        return Successful(mapOf("nodes" to JsonArray(payload), "platformVersion" to Main.VERSION))
    }
}
