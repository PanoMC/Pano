package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeJavaCatalog
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.JavaCatalogMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/**
 * A node's Java runtimes and what it could download (`GET /api/panel/nodes/:id/java`, SM-63).
 *
 * Asks the node (`JAVA_CATALOG`) whenever it can, because only the node knows which server runs
 * from which runtime and what Adoptium or Azul offer for its OS and architecture. When it cannot —
 * the node is offline, too old to know the message, or did not answer in ten seconds — the answer
 * is built from the runtimes stored at its last hello instead of failing, so the card still shows
 * what the host has. The shape is the same either way; see [NodeJavaCatalog].
 */
@Endpoint
class PanelGetNodeJavaAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/nodes/:id/java", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        val node = databaseManager.nodeDao.getById(id, sqlClient) ?: throw NotExists()

        val stored = node.resources
        val supported = stored.javaDownloads

        if (!nodeManager.isConnected(id)) {
            return Successful(
                NodeJavaCatalog.fallback(stored, online = false, supported = supported, os = node.os, arch = node.arch).map
            )
        }

        // An old node never answers, so it is not asked: waiting out the timeout on every visit to
        // its page would buy nothing but a slow card.
        if (!supported) {
            return Successful(
                NodeJavaCatalog.fallback(stored, online = true, supported = false, os = node.os, arch = node.arch).map
            )
        }

        val reply = try {
            nodeManager.request(id, JavaCatalogMessage(), CATALOG_TIMEOUT_MS)
        } catch (_: NodeOffline) {
            // The same exception covers "went away" and "did not answer"; which one it was decides
            // what the card says.
            return Successful(
                NodeJavaCatalog.fallback(
                    stored,
                    online = nodeManager.isConnected(id),
                    supported = true,
                    os = node.os,
                    arch = node.arch,
                    catalogError = if (nodeManager.isConnected(id)) NodeJavaCatalog.ERROR_TIMEOUT else null
                ).map
            )
        }

        val serversByUuid = databaseManager.serverDao.getAllByNodeId(id, sqlClient)
            .mapNotNull { server ->
                server.uuid?.let { it to NodeJavaCatalog.ServerRef(server.id, server.customName ?: server.name) }
            }
            .toMap()

        return Successful(NodeJavaCatalog.fromReply(reply, stored, serversByUuid, node.os, node.arch).map)
    }

    companion object {
        /** §2.4.28: the node resolves six majors against two vendors, each cached for an hour. */
        private const val CATALOG_TIMEOUT_MS = 10_000L
    }
}
