package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * What deleting a node would take with it (`GET /api/panel/nodes/:id/delete-preview`, SM-64).
 *
 * `{ online, uninstallSupported, servers: [{ id, name }], backupCount, backupBytes, javaRuntimes }`
 * — `javaRuntimes` is the number of runtimes the node downloaded itself (the ones an uninstall
 * deletes; a system JDK is never touched), `backupBytes` what the backups occupy on disk (a snapshot
 * counts what it actually stored). Everything comes from Pano's own rows and the node's last hello,
 * so the delete modal opens instantly even for a node that is offline.
 */
@Endpoint
class PanelGetNodeDeletePreviewAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id/delete-preview", RouteType.GET))

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

        val backups = servers.flatMap { databaseManager.serverBackupDao.getAllByServerId(it.id, sqlClient) }

        return Successful(
            mapOf(
                "online" to (node.approved && nodeManager.isConnected(id)),
                "uninstallSupported" to NodeProtocol.supportsUninstall(node.protocolVersion),
                "servers" to servers.map { mapOf("id" to it.id, "name" to (it.customName ?: it.name)) },
                "backupCount" to backups.size,
                "backupBytes" to backups.sumOf { it.storedBytes ?: it.sizeBytes },
                "javaRuntimes" to node.resources.javaRuntimes.count { it.managed }
            )
        )
    }
}
