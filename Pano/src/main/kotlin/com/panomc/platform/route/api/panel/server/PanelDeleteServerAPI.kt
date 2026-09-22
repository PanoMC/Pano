package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DeletedServerLog
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeRemovalService
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.message.DeleteServerMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.InPlaceServerRules
import com.panomc.platform.server.ServerRemovalService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.util.UUID

/**
 * Removes a server from Pano.
 *
 * A linked server is simply forgotten — Pano never owned anything of it. A managed server's files
 * are on a node, so the node is asked to delete them and the row stays until it reports success:
 * that way a failed delete leaves a server an admin can see and retry rather than an unreachable
 * directory. `force` is the escape hatch for a node that is gone for good; it drops the row
 * immediately and says so in the activity log. The files — the directory and, since SM-64, its
 * backups — are then removed by the node the next time it connects: the uuid goes on the node's
 * pending-deletion list and its hello is answered with `DELETE_SERVER`.
 *
 * A server run in place (`inPlace`, the "Pano Agent" link) goes the same way, but the node keeps its
 * directory: only what the node put there and its backups are removed. Every response carries
 * `filesKept` so the panel can say which of the two happened: true for such a server and for a
 * linked one (whose files were never Pano's), false when the node deletes the directory.
 *
 * A server run by a Pano Agent takes its agent with it: the agent was installed for this one server,
 * so after `DELETE_SERVER` (files kept) the agent node is removed exactly like a deleted node --
 * `NODE_UNINSTALL`, then its rows -- through [NodeRemovalService]. An agent that is offline is
 * removed from Pano only, and the response's `manualSteps` say what is left on its machine. The
 * response is then `{ pending: false, filesKept: true, agentRemoved: true, removedFiles,
 * manualSteps }`; `NODE_UNINSTALL_FAILED` comes back unless `force` is set, like a node delete.
 */
@Endpoint
class PanelDeleteServerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val nodeManager: NodeManager,
    private val serverRemovalService: ServerRemovalService,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val nodeRemovalService: NodeRemovalService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("currentPassword", Schemas.stringSchema())
                        .optionalProperty("force", Schemas.booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val currentPassword = data.getString("currentPassword")
        val force = data.getBoolean("force", false)
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, currentPassword, sqlClient)) {
            throw CurrentPasswordNotCorrect()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()
        val name = server.customName ?: server.name

        val nodeId = server.nodeId
        val uuid = server.uuid

        val agentNode = if (server.isManaged && nodeId != null) {
            databaseManager.nodeDao.getById(nodeId, sqlClient)?.takeIf { it.agent }
        } else {
            null
        }

        if (agentNode != null) {
            val online = nodeManager.isConnected(agentNode.id)

            // First the server, which the node releases (its folder stays), then the agent itself:
            // it was dedicated to this server and has nothing else to do.
            if (online && uuid != null) {
                nodeManager.sendMessage(agentNode.id, DeleteServerMessage(uuid, UUID.randomUUID().toString()))
            }

            val outcome = nodeRemovalService.delete(agentNode, userId, username, force || !online, sqlClient)

            return Successful(
                mapOf(
                    "pending" to false,
                    "filesKept" to true,
                    "agentRemoved" to true,
                    "removedFiles" to outcome.removedFiles,
                    "manualSteps" to outcome.manualSteps
                )
            )
        }

        var task: ServerTask? = null

        if (server.isManaged && !force && nodeId != null && uuid != null && nodeManager.isConnected(nodeId)) {
            val now = System.currentTimeMillis()

            val pending = ServerTask(
                uuid = UUID.randomUUID().toString(),
                serverId = id,
                nodeId = nodeId,
                kind = ServerTaskKind.DELETE,
                status = ServerTaskStatus.PENDING,
                createdBy = userId,
                createdAt = now,
                updatedAt = now
            )

            val taskId = databaseManager.serverTaskDao.add(pending, sqlClient)

            task = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: pending

            val sent = nodeManager.sendMessage(nodeId, DeleteServerMessage(uuid, pending.uuid))

            if (sent) {
                panelRealtimeHub.pushTaskProgress(task)

                databaseManager.panelActivityLogDao.add(
                    DeletedServerLog(userId, username, id, name, false),
                    sqlClient
                )

                return Successful(
                    mapOf(
                        "taskId" to task.id,
                        "taskUuid" to task.uuid,
                        "pending" to true,
                        "filesKept" to InPlaceServerRules.filesKeptOnRemoval(server)
                    )
                )
            }

            // The socket went away between the check and the send. Fall through and remove the row
            // rather than leaving the admin with a server that refuses to disappear.
            databaseManager.serverTaskDao.updateProgressByUuid(
                uuid = pending.uuid,
                status = ServerTaskStatus.FAILED,
                percent = 0,
                message = null,
                error = "node disconnected",
                updatedAt = System.currentTimeMillis(),
                sqlClient = sqlClient
            )
        }

        if (server.isManaged && nodeId != null && uuid != null) {
            // Remembered whether or not the node is reachable right now (SM-64, §2.4.29 A): the
            // push below is fire and forget, and a node that is offline — or fails the delete —
            // is told again on its next hello, until it stops reporting the server.
            databaseManager.nodePendingDeletionDao.add(nodeId, uuid, sqlClient)

            // Best effort on a forced delete: the files are cleaned up now if the node happens to
            // be reachable, and on its next connect if it is not.
            if (nodeManager.isConnected(nodeId)) {
                nodeManager.sendMessage(nodeId, DeleteServerMessage(uuid, UUID.randomUUID().toString()))
            }
        }

        serverRemovalService.remove(id, sqlClient)

        databaseManager.panelActivityLogDao.add(
            DeletedServerLog(userId, username, id, name, server.isManaged),
            sqlClient
        )

        return Successful(mapOf("pending" to false, "filesKept" to InPlaceServerRules.filesKeptOnRemoval(server)))
    }
}
