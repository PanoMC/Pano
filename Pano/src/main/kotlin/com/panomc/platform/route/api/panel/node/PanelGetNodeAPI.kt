package com.panomc.platform.route.api.panel.node

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodeJarProvider
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeUpdateAvailability
import com.panomc.platform.node.NodeUpdateProgressStore
import com.panomc.platform.server.ServerActiveTaskStore
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * One node with its live metrics and the servers currently placed on it.
 *
 * Also answers whether the daemon on it is behind the one this Pano serves, which is what puts an
 * "update available" badge on the node and enables `POST /api/panel/nodes/:id/update`. The
 * comparison is version against version, except for development builds where both sides are
 * `local-build` forever and only the jar's checksum can tell them apart — see
 * [NodeUpdateAvailability].
 */
@Endpoint
class PanelGetNodeAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val nodeJarProvider: NodeJarProvider,
    private val activeTaskStore: ServerActiveTaskStore,
    private val nodeUpdateProgressStore: NodeUpdateProgressStore
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        val servers = databaseManager.serverDao.getAllByNodeId(id, sqlClient)

        val jar = nodeJarProvider.locate()

        // Null when this install has no daemon jar to hand out; nothing is then offered, because
        // an update Pano cannot deliver is not an update.
        val servedSha256 = jar?.let { nodeJarProvider.sha256(it) }

        return Successful(
            mapOf(
                "node" to node.toPublicJsonObject()
                    .put("connected", nodeManager.isConnected(id))
                    .put("metrics", nodeManager.getLatestMetrics(id)?.toJsonObject())
                    // The version to compare the node's against, so the panel can say what it
                    // would be updating to rather than only that it could.
                    .put("platformVersion", Main.VERSION)
                    .put("jarSha256", nodeManager.getJarSha256(id))
                    // `{ version, status, percent, message }` while its daemon updates, and the
                    // version an update installs (null for a development build) (SM-77).
                    .put("updateProgress", nodeUpdateProgressStore.get(id)?.toNodeJsonObject())
                    .put("latestVersion", NodeInstallScriptProvider.releaseVersion())
                    .put(
                        "updateAvailable",
                        NodeUpdateAvailability.isAvailable(
                            nodeVersion = node.version,
                            platformVersion = Main.VERSION,
                            nodeJarSha256 = nodeManager.getJarSha256(id),
                            servedSha256 = servedSha256
                        )
                    ),
                // `activeTask` as on every other server JSON, for the Servers card's progress bar
                // (SM-68).
                "servers" to JsonArray(
                    servers.map { it.toPublicJsonObject().put("activeTask", activeTaskStore.get(it.id)?.toJsonObject()) }
                )
            )
        )
    }
}
