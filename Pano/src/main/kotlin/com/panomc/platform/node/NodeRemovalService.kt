package com.panomc.platform.node

import com.panomc.platform.auth.panel.log.DeletedNodeLog
import com.panomc.platform.auth.panel.log.DeletedServerLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NodeUninstallFailed
import com.panomc.platform.node.message.NodeUninstallMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerRemovalService
import com.panomc.platform.token.NodeAuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deletes a node and everything on it (SM-64, §2.4.29 B).
 *
 * The user's decision was that deleting a node deletes everything on it. That is two halves that
 * can fail independently, and the order is what keeps a failure harmless:
 *
 * 1. **The host.** An online node that speaks protocol 4 is sent `NODE_UNINSTALL` and the request
 *    waits (two minutes) for the task to end: servers stopped and deleted, backups, Java runtimes
 *    and caches gone, the service removed or the commands for it returned. Pano's own local node
 *    stops being supervised *before* that, or the uninstall's exit would be answered with a restart.
 * 2. **Pano.** Only then does every server on the node go through [ServerRemovalService] — the same
 *    clean-up a single delete does, one activity entry each — followed by the node's own rows.
 *
 * A node that cannot do step 1 (offline, older than protocol 4, failed, timed out) is refused with
 * `NODE_OFFLINE` / `NODE_UNINSTALL_FAILED` unless the admin forces it; a forced delete is step 2
 * alone, with the manual steps Pano can work out from what the node told it. The local node is the
 * exception to "the files stay": its `node-data` is Pano's, and it is removed here once the daemon
 * is confirmed gone.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeRemovalService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val tokenProvider: TokenProvider,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverRemovalService: ServerRemovalService,
    private val serverTaskService: ServerTaskService,
    private val localNodeManager: LocalNodeManager,
    private val logger: Logger
) {
    /** What a delete did, which is also the endpoint's response. */
    data class Outcome(
        val removedFiles: Boolean,
        val serversDeleted: Int,
        val manualSteps: List<String>
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "removedFiles" to removedFiles,
            "serversDeleted" to serversDeleted,
            "manualSteps" to manualSteps
        )
    }

    /**
     * Deletes [node]. Throws [NodeOffline] / [NodeUninstallFailed] (with `manualSteps` and
     * `serverCount`) when the node could not remove itself and [force] is false; nothing has been
     * changed then, apart from whatever the node itself did before it failed.
     */
    suspend fun delete(node: Node, userId: Long, username: String, force: Boolean, sqlClient: SqlClient): Outcome {
        val servers = databaseManager.serverDao.getAllByNodeId(node.id, sqlClient)

        val online = node.approved && nodeManager.isConnected(node.id)
        val decision = NodeRemovalDecision.decide(
            online = online,
            uninstallSupported = NodeProtocol.supportsUninstall(node.protocolVersion),
            force = force
        )

        val fallbackSteps = NodeRemovalSteps.build(node.bootstrap, node.kind, node.os, node.dataPath, node.runtime)
        val local = node.kind == NodeKind.LOCAL

        fun refusal(reason: String?): Map<String, Any?> = mapOf(
            "nodeError" to reason,
            "manualSteps" to fallbackSteps,
            "serverCount" to servers.size
        )

        when (decision) {
            NodeRemovalDecision.REFUSE_OFFLINE -> throw NodeOffline(extras = refusal(null) - "nodeError")
            NodeRemovalDecision.REFUSE_TOO_OLD -> throw NodeUninstallFailed(extras = refusal(NODE_TOO_OLD))
            else -> Unit
        }

        // Before the uninstall is sent, never after: its last act is exiting, and a supervisor that
        // is still watching would start the daemon again straight away.
        if (local) {
            localNodeManager.beginRetire()
        }

        var removedFiles = false
        var manualSteps = fallbackSteps

        // Whichever way the socket closes from here on — the node exiting after its uninstall, or
        // Pano cutting it off below — it is the deletion, not an outage to alert about.
        nodeManager.setRemoving(node.id, true)

        if (decision == NodeRemovalDecision.UNINSTALL) {
            val result = try {
                uninstall(node, userId, sqlClient)
            } catch (throwable: Throwable) {
                nodeManager.setRemoving(node.id, false)

                if (local) {
                    localNodeManager.cancelRetire()
                }

                throw throwable
            }

            if (result.status == ServerTaskStatus.DONE) {
                removedFiles = true
                manualSteps = result.manualSteps.orEmpty()
            } else if (!force) {
                nodeManager.setRemoving(node.id, false)

                if (local) {
                    localNodeManager.cancelRetire()
                }

                throw NodeUninstallFailed(extras = refusal(result.error ?: ServerTaskStatus.FAILED.name))
            } else {
                logger.warn(
                    "\"${node.name}\" node could not uninstall itself (${result.error}); removing it from Pano anyway."
                )
            }
        }

        servers.forEach { server ->
            serverRemovalService.remove(server.id, sqlClient)

            databaseManager.panelActivityLogDao.add(
                // "force" means the server's files were left behind, which is true of every managed
                // server here unless the node removed them.
                DeletedServerLog(userId, username, server.id, server.customName ?: server.name, server.isManaged && !removedFiles),
                sqlClient
            )
        }

        // Revoking the token and closing the socket is what actually cuts the node off; deleting
        // the row alone would leave a daemon that keeps reconnecting with a token nothing has
        // invalidated.
        nodeManager.closeConnection(node.id)

        tokenProvider.invalidateTokensBySubjectAndType(node.id.toString(), NodeAuthenticationTokenType, sqlClient)

        databaseManager.serverBackupDao.deleteByNodeId(node.id, sqlClient)
        databaseManager.serverTaskDao.deleteByNodeId(node.id, sqlClient)
        databaseManager.serverAlertDao.deleteByNodeId(node.id, sqlClient)
        databaseManager.nodePendingDeletionDao.deleteByNodeId(node.id, sqlClient)

        databaseManager.nodeDao.deleteById(node.id, sqlClient)

        nodeManager.onNodeDeleted(node.id)

        // The row is gone now, which is what keeps a late disconnect from raising an alert.
        nodeManager.setRemoving(node.id, false)

        if (local) {
            // Pano created node-data, so Pano removes it — after an uninstall it holds only the
            // retired marker, after a forced delete it holds everything.
            val retired = localNodeManager.finishRetire(kill = !removedFiles)

            manualSteps = localSteps(retired)
            removedFiles = retired.dataDirRemoved || removedFiles
        }

        databaseManager.panelActivityLogDao.add(
            DeletedNodeLog(userId, username, node.id, node.name, servers.size.toLong(), removedFiles),
            sqlClient
        )

        panelRealtimeHub.notifyNodeRemoved(node.id)

        logger.info(
            "Deleted \"${node.name}\" node with ${servers.size} server(s)" +
                if (removedFiles) "; the node removed its files." else "; its files were left on the machine."
        )

        return Outcome(removedFiles, servers.size, manualSteps)
    }

    /**
     * What is left to do on Pano's own machine once the local node is gone: nothing, unless the
     * data directory could not be removed or a Docker server's container outlived its daemon.
     */
    private fun localSteps(result: LocalNodeManager.RetireResult): List<String> {
        val steps = mutableListOf<String>()

        if (!result.dataDirRemoved) {
            result.error?.let { steps.add("# $it") }
            steps.add("rm -rf ${NodeRemovalSteps.shellQuote(result.dataDir)}")
        }

        result.containers.forEach { steps.add("docker rm -f ${NodeRemovalSteps.shellQuote(it)}") }

        return steps
    }

    /**
     * Sends `NODE_UNINSTALL` and waits for its task to end, the node to disconnect, or the timeout.
     *
     * The task row is written before the push so the node's first frame lands on a task Pano has,
     * and it belongs to the user who pressed Delete: their panel follows the progress over the
     * usual `taskProgress` frames.
     */
    private suspend fun uninstall(node: Node, userId: Long, sqlClient: SqlClient): ServerTaskService.TaskOutcome {
        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = null,
            nodeId = node.id,
            kind = ServerTaskKind.NODE_UNINSTALL,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = "Removing the node from its machine",
            createdBy = userId,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverTaskDao.add(task, sqlClient)
        val stored = databaseManager.serverTaskDao.getById(id, sqlClient) ?: task

        val waiter = serverTaskService.expectTerminal(task.uuid)

        if (!nodeManager.sendMessage(node.id, NodeUninstallMessage(task.uuid))) {
            return fail(stored, NODE_DISCONNECTED, sqlClient)
        }

        panelRealtimeHub.pushTaskProgress(stored)

        val outcome = await(waiter, node.id)

        if (outcome != null) {
            return outcome
        }

        val reason = if (nodeManager.isConnected(node.id)) ServerTaskTimeout.TIMEOUT_ERROR else NODE_DISCONNECTED

        return fail(stored, reason, sqlClient)
    }

    /**
     * The task's end, or null after [UNINSTALL_TIMEOUT_MS] or once the node has been gone for a few
     * seconds without saying how it ended. The grace is there because a node that finished closes
     * its socket a second after the DONE, and the two may be handled in either order here.
     */
    private suspend fun await(
        waiter: CompletableDeferred<ServerTaskService.TaskOutcome>,
        nodeId: Long
    ): ServerTaskService.TaskOutcome? {
        val deadline = System.currentTimeMillis() + UNINSTALL_TIMEOUT_MS
        var goneSince: Long? = null

        while (System.currentTimeMillis() < deadline) {
            withTimeoutOrNull(POLL_MS) { waiter.await() }?.let { return it }

            val now = System.currentTimeMillis()

            if (nodeManager.isConnected(nodeId)) {
                goneSince = null
            } else {
                val since = goneSince ?: now.also { goneSince = it }

                if (now - since >= DISCONNECT_GRACE_MS) {
                    return if (waiter.isCompleted) waiter.await() else null
                }
            }
        }

        return if (waiter.isCompleted) waiter.await() else null
    }

    private suspend fun fail(task: ServerTask, reason: String, sqlClient: SqlClient): ServerTaskService.TaskOutcome {
        serverTaskService.forgetTerminal(task.uuid)

        var result = ServerTaskService.TaskOutcome(ServerTaskStatus.FAILED, reason)

        serverTaskService.withTaskLock(task.uuid) {
            val current = databaseManager.serverTaskDao.getByUuid(task.uuid, sqlClient) ?: return@withTaskLock true

            // A frame that ended it after all, between the last check and this lock, is the answer.
            if (current.status.isTerminal) {
                result = ServerTaskService.TaskOutcome(current.status, current.error)

                return@withTaskLock true
            }

            current.status = ServerTaskStatus.FAILED
            current.error = reason
            current.updatedAt = System.currentTimeMillis()

            databaseManager.serverTaskDao.updateProgressByUuid(
                uuid = current.uuid,
                status = current.status,
                percent = current.percent,
                message = current.message,
                error = current.error,
                updatedAt = current.updatedAt,
                sqlClient = sqlClient
            )

            panelRealtimeHub.pushTaskProgress(current)

            true
        }

        return result
    }

    companion object {
        /** How long a delete waits for the node to finish uninstalling (§2.4.29 B). */
        const val UNINSTALL_TIMEOUT_MS = 120_000L

        private const val POLL_MS = 500L
        private const val DISCONNECT_GRACE_MS = 3_000L

        /** `nodeError` for a node that is online but too old to know `NODE_UNINSTALL`. */
        const val NODE_TOO_OLD = "NODE_TOO_OLD"

        /** `nodeError` for a node that went away before saying how the uninstall ended. */
        const val NODE_DISCONNECTED = "NODE_DISCONNECTED"
    }
}

/**
 * What deleting a node does, from the three facts that decide it (SM-64). Pure, for the tests.
 */
enum class NodeRemovalDecision {
    /** Online and new enough: the node uninstalls itself, then Pano drops the rows. */
    UNINSTALL,

    /** Cannot uninstall, but forced: Pano drops the rows and hands back manual steps. */
    FORGET,

    /** Offline and not forced. */
    REFUSE_OFFLINE,

    /** Online but older than protocol 4, and not forced. */
    REFUSE_TOO_OLD;

    companion object {
        fun decide(online: Boolean, uninstallSupported: Boolean, force: Boolean): NodeRemovalDecision = when {
            online && uninstallSupported -> UNINSTALL
            force -> FORGET
            online -> REFUSE_TOO_OLD
            else -> REFUSE_OFFLINE
        }
    }
}
