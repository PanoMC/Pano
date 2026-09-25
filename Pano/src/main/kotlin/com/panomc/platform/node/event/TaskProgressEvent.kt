package com.panomc.platform.node.event

import com.panomc.platform.server.TaskTransfer
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ImportStartHandoff
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeUpdateProgressService
import com.panomc.platform.node.ServerInstallFailure
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.ServerTaskService
import com.panomc.platform.node.ServerTaskTransition
import com.panomc.platform.node.event.request.TaskProgressEventRequest
import com.panomc.platform.node.message.PowerMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerPowerAction
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerRemovalService
import com.panomc.platform.server.plugins.ManagedServerPluginService
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import java.util.UUID

/**
 * Progress of a long-running node job (`TASK_PROGRESS`).
 *
 * Besides recording the numbers this is where a finished task has its consequences: an install
 * that reports DONE is what turns an INSTALLING row into a server that can be started, and a
 * delete that reports DONE is what finally removes the row — Pano deliberately keeps the server
 * visible until the node confirms the files are gone, so a failed delete leaves something an admin
 * can see and retry instead of an orphaned directory nobody can reach.
 *
 * Frames for one task are applied one at a time. A node's socket is read in order and
 * `NodeConnectAPI` launches a coroutine per frame, so the handlers *start* in order but suspend on
 * their first database call and finish in whatever order the pool hands them back. Reading the
 * stored progress, deciding on it, writing it and pushing it to the panel is therefore done under
 * a mutex keyed by the task — [ServerTaskService]'s, shared with the timeout sweep — and the push
 * carries what was just written rather than what arrived: without it a burst of download progress
 * interleaves and the panel counts 60, 61, 54, 55, or is told a task is Configuring before it is
 * told it is 78 % downloaded.
 */
@Event
class TaskProgressEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverRemovalService: ServerRemovalService,
    private val serverTaskService: ServerTaskService,
    private val managedServerPluginService: ManagedServerPluginService,
    private val nodeUpdateProgressService: NodeUpdateProgressService,
    private val logger: Logger
) : NodeEvent<TaskProgressEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: TaskProgressEventRequest, node: Node): NodeEventResponse? {
        val taskId = request.taskId ?: return null

        // The daemon updating itself (SM-77): no server, no row, keyed on the version it installs.
        // It used to be dropped here for having no row; now it is the node's `updateProgress` and
        // its servers' `daemonUpdate`. Handled before anything suspends, so frames stay in order.
        if (ServerTaskKind.fromId(request.kind) == ServerTaskKind.SELF_UPDATE && request.serverUuid == null) {
            nodeUpdateProgressService.onFrame(node, request)

            return null
        }

        serverTaskService.withTaskLock(taskId) { apply(taskId, request, node.id, null) }

        return null
    }

    /**
     * The same frame arriving from a server's own Pano plugin (SM-47, §2.4.17 C).
     *
     * An agent-lite plugin runs backups and installs itself and reports them exactly as a node
     * does, so there is one implementation and only the ownership rule differs: a node may speak
     * about its own tasks, a plugin about its own server's.
     */
    suspend fun handleFromServer(request: TaskProgressEventRequest, serverId: Long) {
        val taskId = request.taskId ?: return

        serverTaskService.withTaskLock(taskId) { apply(taskId, request, null, serverId) }
    }

    /**
     * Applies one frame, and reports whether the task can still receive another.
     *
     * Everything here runs under the task's mutex: the read of the stored progress, the transition
     * decided from it, the write, the panel push and the consequences of a task that ended.
     */
    private suspend fun apply(
        taskId: String,
        request: TaskProgressEventRequest,
        nodeId: Long?,
        serverId: Long?
    ): Boolean {
        val sqlClient = databaseManager.getSqlClient()

        // Gone means gone: nothing else can arrive for a task whose row no longer exists, so the
        // lock entry is dropped with it.
        val task = databaseManager.serverTaskDao.getByUuid(taskId, sqlClient)
            ?: openNodeTask(taskId, request, nodeId, sqlClient)
            ?: return true

        // A node may only speak about its own tasks, and a plugin only about its own server's --
        // the same rule server uuids follow in the other direction.
        val owned = if (nodeId != null) task.nodeId == nodeId else task.serverId == serverId

        if (!owned) {
            logger.warn("Progress reported for task ${task.uuid} by something that does not own it, ignoring.")

            return false
        }

        val reportedStatus = ServerTaskStatus.fromId(request.status)

        // Rejects anything that would move a task backwards or reopen one that already ended,
        // which is what makes a late frame harmless rather than a progress bar that jumps back.
        val progress = ServerTaskTransition.apply(
            ServerTaskTransition.Progress(task.status, task.percent),
            reportedStatus,
            request.percent
        ) ?: return task.status.isTerminal

        task.status = progress.status
        task.percent = progress.percent
        task.message = request.message?.take(MAX_TEXT_LENGTH)
        task.error = request.error?.take(MAX_TEXT_LENGTH)
        task.updatedAt = System.currentTimeMillis()

        databaseManager.serverTaskDao.updateProgressByUuid(
            uuid = task.uuid,
            status = task.status,
            percent = task.percent,
            message = task.message,
            error = task.error,
            updatedAt = task.updatedAt,
            sqlClient = sqlClient
        )

        // After the write and still inside the lock, carrying the row that was just stored: a push
        // that raced the write is how the panel ends up showing a percentage the database never
        // held.
        // A download's bytes and rate ride on the push only: they are about this second, so they
        // go to the panel and the in-memory active task, never into the row.
        val transfer = if (task.status == ServerTaskStatus.RUNNING) {
            TaskTransfer.of(request.bytesDone, request.bytesTotal, request.bytesPerSecond)
        } else {
            null
        }

        panelRealtimeHub.pushTaskProgress(task, transfer)

        if (task.status.isTerminal) {
            serverTaskService.completeTerminal(
                task.uuid,
                ServerTaskService.TaskOutcome(
                    status = task.status,
                    error = task.error,
                    removedBytes = request.removedBytes,
                    manualSteps = request.manualSteps
                        ?.filterNotNull()
                        ?.map { it.take(MAX_TEXT_LENGTH) }
                        ?.take(MAX_MANUAL_STEPS)
                )
            )
        }

        if (task.status == ServerTaskStatus.DONE) {
            // A DONE that quietly left the server unlinked is the one success worth logging: the
            // panel shows the sentence on the task, but the reason lives in the node's console and
            // an operator reading Pano's log should be pointed at it.
            if (request.pluginInstalled == false) {
                logger.warn(
                    "Node $nodeId finished ${task.kind.name} task ${task.uuid} without installing " +
                        "the Pano plugin; that server is not linked. ${task.message.orEmpty()}"
                )
            }

            onTaskDone(task.kind, task.serverId, nodeId, task.uuid)
        }

        if (task.status == ServerTaskStatus.FAILED) {
            serverTaskService.takeStartAfter(task.uuid)
            serverTaskService.takeStartCarriedByLink(task.uuid)

            serverTaskService.onTaskFailed(task.kind, task.serverId, task.error, sqlClient)
        }

        // Both ends of a plugin install settle the provenance row it created, which is why this
        // sits outside the DONE branch: a failed download must take its row with it, or the next
        // update check offers an update to a version that was never installed.
        if (task.status.isTerminal && task.kind == ServerTaskKind.PLUGIN_INSTALL) {
            managedServerPluginService.onInstallTaskFinished(task.uuid, task.serverId, task.status, sqlClient)
        }

        return task.status.isTerminal
    }

    private suspend fun onTaskDone(kind: ServerTaskKind, serverId: Long?, nodeId: Long?, taskUuid: String) {
        val sqlClient = databaseManager.getSqlClient()
        val id = serverId ?: return

        when (kind) {
            ServerTaskKind.INSTALL, ServerTaskKind.REINSTALL, ServerTaskKind.IMPORT, ServerTaskKind.RESTORE -> {
                val server = databaseManager.serverDao.getById(id, sqlClient) ?: return

                // Installed now, whatever failed before (ServerInstallFailure).
                if (server.installError != null && ServerInstallFailure.clearsOnDone(kind)) {
                    databaseManager.serverDao.updateInstallErrorById(id, null, sqlClient)
                }

                databaseManager.serverDao.updateProcessStateById(id, ServerProcessState.STOPPED, null, sqlClient)

                panelRealtimeHub.pushServerState(id, ServerProcessState.STOPPED.name, null, null, null)
                panelRealtimeHub.notifyServerUpdated(id)

                // The node has a new process for this server, which an older node does not stream
                // to a console that was already open.
                panelRealtimeHub.onServerReinstalled(id)

                val uuid = server.uuid

                // A software change said up front whether it wants the server back (SM-66); every
                // other install follows the row's auto-start.
                val start = serverTaskService.takeStartAfter(taskUuid) ?: server.autoStart

                // An import whose Pano plugin install went out with this start leaves it to that
                // install: the node starts the server once the jar is in, where a START from here
                // booted it mid-download (ImportStartHandoff). Every other task -- and an import
                // that got no install -- starts here as it always did.
                val carriedByLink = serverTaskService.takeStartCarriedByLink(taskUuid)

                if (ImportStartHandoff.startsOnDone(start, carriedByLink) && uuid != null && nodeId != null) {
                    nodeManager.sendMessage(
                        nodeId,
                        PowerMessage(
                            serverUuid = uuid,
                            action = ServerPowerAction.START.name,
                            requestId = UUID.randomUUID().toString(),
                            issuedBy = AUTO_START_ISSUER
                        )
                    )
                }
            }

            ServerTaskKind.DELETE -> {
                serverRemovalService.remove(id, sqlClient)
            }

            // A bootstrap never reaches here — it has no server and is not reported by a node —
            // and is listed so a new kind cannot be added without deciding what finishing means.
            ServerTaskKind.BACKUP, ServerTaskKind.SELF_UPDATE, ServerTaskKind.NODE_BOOTSTRAP -> Unit

            // The node follows a finished Java install or removal with `NODE_JAVA_RUNTIMES`, and
            // that frame is what refreshes the node; a Java install a start triggered hands back to
            // the start, whose SERVER_STATE frames say how it went.
            ServerTaskKind.JAVA_INSTALL, ServerTaskKind.JAVA_REMOVE -> Unit

            // Node-scoped, and its consequences — every server row, the node row — are applied by
            // the delete request that is waiting on it (NodeRemovalService), not here.
            ServerTaskKind.NODE_UNINSTALL -> Unit

            // The jar is on disk, but the game only reads its plugin directory at boot, so the
            // panel is nudged to re-read the file list and show the "restart to load" state.
            ServerTaskKind.PLUGIN_INSTALL -> panelRealtimeHub.pushServerPluginsChanged(id)
        }
    }

    /**
     * Creates the row for a task the node started on its own, or returns null to drop the frame.
     *
     * Only kinds that [ServerTaskKind.mayBeOpenedByNode] qualify — today the Java download a start
     * needed (SM-63, §2.4.28) — and only from a node: a plugin never opens tasks. The server, when
     * the frame names one, has to be this node's, exactly like every other uuid a node sends. The
     * row belongs to nobody ([NODE_USER_ID]), so its progress reaches the panels watching nodes.
     */
    private suspend fun openNodeTask(
        taskId: String,
        request: TaskProgressEventRequest,
        nodeId: Long?,
        sqlClient: SqlClient
    ): ServerTask? {
        val node = nodeId?.let { nodeManager.getConnectedNodeById(it) } ?: return null
        val kind = ServerTaskKind.fromId(request.kind)?.takeIf { it.mayBeOpenedByNode } ?: return null

        if (!isTaskUuid(taskId)) {
            return null
        }

        val server = request.serverUuid?.let { nodeManager.resolveServer(node, it, sqlClient) }

        // A uuid was named and it is not one of this node's servers: the frame is refused rather
        // than filed as node-scoped, which would make it look like somebody asked for it.
        if (request.serverUuid != null && server == null) {
            return null
        }

        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = taskId,
            serverId = server?.id,
            nodeId = node.id,
            kind = kind,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = request.message?.take(MAX_TEXT_LENGTH),
            createdBy = NODE_USER_ID,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverTaskDao.add(task, sqlClient)

        return databaseManager.serverTaskDao.getById(id, sqlClient) ?: task
    }

    companion object {
        private const val MAX_TEXT_LENGTH = 2000

        /** More lines than any installer needs; a node cannot make the panel render a novel. */
        private const val MAX_MANUAL_STEPS = 20

        /** `createdBy` of a task the node opened itself. No user row has a negative id. */
        const val NODE_USER_ID = -1L

        private val TASK_UUID_PATTERN = Regex("^[0-9a-fA-F-]{8,36}$")

        /** Whether [id] fits the task table's uuid column and looks like one. */
        fun isTaskUuid(id: String): Boolean = TASK_UUID_PATTERN.matches(id)

        /** Shown as the issuer of a start nobody pressed a button for. */
        private const val AUTO_START_ISSUER = "auto-start"
    }
}
