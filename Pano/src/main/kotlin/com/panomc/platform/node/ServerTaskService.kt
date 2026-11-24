package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerActiveTaskStore
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.plugins.ManagedServerPluginService
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bookkeeping around node tasks that does not belong to any one frame.
 *
 * Two things live here. The first is the serialisation of everything that touches one task, so a
 * burst of progress frames and the timeout sweep cannot interleave on the same row. The second is
 * the sweep itself, and what a failed task means for the server it belongs to — which is shared
 * with [com.panomc.platform.node.event.TaskProgressEvent] precisely because a task that timed out
 * has to leave the system in the same shape as one the node said had failed. Anything less and a
 * timed-out install would be FAILED on the tasks page while the server row stayed INSTALLING
 * forever, which is the bug this class exists to end, not a nicer version of it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerTaskService(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val alertManager: AlertManager,
    private val activeTaskStore: ServerActiveTaskStore,
    private val vertx: Vertx,
    private val logger: Logger,
    // Looked up when used, not at construction: both services sit above this one in the graph.
    private val managedServerPluginService: ObjectProvider<ManagedServerPluginService>,
    private val panoPluginUpdateService: ObjectProvider<PanoPluginUpdateService>,
    private val nodeDaemonUpdateService: ObjectProvider<NodeDaemonUpdateService>,
    private val serverRemovalService: ObjectProvider<com.panomc.platform.server.ServerRemovalService>
) {
    /**
     * Tasks whose server goes away again if they fail: an import from an archive the browser sends
     * after the row was created. If the archive never arrives, breaks off or does not fit, the row is
     * a server without files that nobody asked to keep (owner decision, 2026-11-25), so it is removed
     * instead of being left STOPPED with an error. In memory: a Pano restart in the middle of such an
     * upload already loses the upload, and the row then stays with its error to be deleted by hand.
     */
    private val removeOnFailure = ConcurrentHashMap.newKeySet<String>()

    /** Marks [uuid] as an import whose server is removed when it fails. */
    fun removeServerOnFailure(uuid: String) {
        removeOnFailure.add(uuid)
    }

    /**
     * One mutex per task uuid.
     *
     * Frames of the same task never overlap while frames of other tasks and other nodes still run
     * in parallel, and the sweep takes the same lock, so a task cannot be failed for silence in
     * the middle of the frame that would have broken that silence. Entries are dropped once a task
     * can no longer receive updates, which is the only reason this map does not grow for the
     * lifetime of the process.
     */
    private val taskLocks = ConcurrentHashMap<String, Mutex>()

    private val timeoutSweepStarted = AtomicBoolean(false)

    /**
     * How a task ended, for the one caller that waits on a task inside a request (SM-64's node
     * uninstall). [extras] is the terminal frame's payload beyond the usual fields.
     */
    data class TaskOutcome(
        val status: ServerTaskStatus,
        val error: String? = null,
        val removedBytes: Long? = null,
        val manualSteps: List<String>? = null
    )

    /**
     * Callers suspended until a task ends, by task uuid.
     *
     * Nearly every task is followed by the panel over the hub and nobody in Pano waits on it. The
     * node uninstall is the exception: deleting a node has to know whether the node really removed
     * itself before it drops the rows, so its request waits here for the terminal frame.
     */
    private val terminalWaiters = ConcurrentHashMap<String, CompletableDeferred<TaskOutcome>>()

    /**
     * Whether a server should be started once its reinstall task is DONE, by task uuid (SM-66).
     *
     * A software change decides this up front (`startAfter`, default: it was running when asked),
     * and the decision replaces the row's auto-start for that one task. Kept in memory only: after
     * a restart of Pano the task falls back to auto-start, which is what a reinstall did before.
     */
    private val startAfterByTask = ConcurrentHashMap<String, Boolean>()

    /** Records whether the server of reinstall task [uuid] is started when the task is DONE. */
    fun setStartAfter(uuid: String, startAfter: Boolean) {
        startAfterByTask[uuid] = startAfter
    }

    /** Takes (and forgets) the start decision of task [uuid]; null when nobody made one. */
    fun takeStartAfter(uuid: String): Boolean? = startAfterByTask.remove(uuid)

    /** The start decision of task [uuid] without taking it; null when nobody made one. */
    fun startAfterOf(uuid: String): Boolean? = startAfterByTask[uuid]

    /**
     * The import tasks whose start went out with the Pano plugin install that follows them, by
     * task uuid ([ImportStartHandoff]).
     *
     * Added by `IMPORT_RESULT` and taken by the same import's DONE, both under the task's lock.
     * Memory only, like [startAfterByTask]: after a restart of Pano an import's DONE starts the
     * server itself, which is what it always did.
     */
    private val startCarriedByLink: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records that import task [uuid]'s start is now the Pano plugin install's to make. */
    fun carryStartInLink(uuid: String) {
        startCarriedByLink.add(uuid)
    }

    /** Takes (and forgets) whether import task [uuid]'s start went out with its plugin install. */
    fun takeStartCarriedByLink(uuid: String): Boolean = startCarriedByLink.remove(uuid)

    /** Registers interest in the end of task [uuid]; call before the node can possibly answer. */
    fun expectTerminal(uuid: String): CompletableDeferred<TaskOutcome> =
        terminalWaiters.computeIfAbsent(uuid) { CompletableDeferred() }

    /** Drops a waiter nobody is going to read any more (a timeout, a failed send). */
    fun forgetTerminal(uuid: String) {
        terminalWaiters.remove(uuid)
    }

    /** Hands a task's end to whoever is waiting on it, if anybody is. */
    fun completeTerminal(uuid: String, outcome: TaskOutcome) {
        // A finished import keeps its server; only a failed one is removed (onTaskFailed).
        if (outcome.status == ServerTaskStatus.DONE) {
            removeOnFailure.remove(uuid)
        }

        terminalWaiters.remove(uuid)?.complete(outcome)
    }

    /**
     * Runs [block] with exclusive access to the task called [uuid].
     *
     * [block] reports whether the task has reached an end state; when it has, the lock entry goes
     * with it, compared by identity so a mutex a concurrent caller is already holding is never
     * pulled out from under them.
     */
    suspend fun withTaskLock(uuid: String, block: suspend () -> Boolean) {
        val lock = taskLocks.computeIfAbsent(uuid) { Mutex() }

        val ended = lock.withLock { block() }

        if (ended) {
            taskLocks.remove(uuid, lock)
        }
    }

    /**
     * Arms the once-a-minute sweep that fails tasks nothing is reporting on any more.
     *
     * Idempotent: the node manager calls it both when it initialises and whenever a node connects,
     * because a fresh install has no nodes at boot and therefore never runs the init path.
     */
    fun startTimeoutSweep() {
        if (!timeoutSweepStarted.compareAndSet(false, true)) {
            return
        }

        // What servers were busy with when this process started (SM-68): the in-memory
        // `activeTask` is otherwise empty until each task's next frame.
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                activeTaskStore.seed(databaseManager.serverTaskDao.getAllUnfinished(databaseManager.getSqlClient()))
            } catch (e: Exception) {
                logger.warn("Could not read unfinished tasks: ${e.message}")
            }
        }

        vertx.setPeriodic(SWEEP_INTERVAL_MS) {
            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    failTimedOutTasks()
                } catch (e: Exception) {
                    // Swallowed on purpose: a sweep that propagates takes the timer with it and
                    // the feature silently stops working, which is the state it was built to fix.
                    logger.warn("Task timeout sweep failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Fails every unfinished task that has been silent past its deadline.
     *
     * Deliberately a read of the unfinished rows rather than an in-memory registry: tasks outlive
     * the process that created them, so after a restart the only place a half-finished install
     * exists is the table.
     */
    suspend fun failTimedOutTasks(now: Long = System.currentTimeMillis()) {
        val sqlClient = databaseManager.getSqlClient()

        val unfinished = databaseManager.serverTaskDao.getAllUnfinished(sqlClient)

        if (unfinished.isEmpty()) {
            return
        }

        unfinished
            .filter { ServerTaskTimeout.hasTimedOut(it.status, it.updatedAt, now) }
            .forEach { candidate ->
                withTaskLock(candidate.uuid) { failAsTimedOut(candidate.uuid, now, sqlClient) }
            }
    }

    /**
     * Writes one task off as timed out, under its lock.
     *
     * The row is re-read inside the lock because the candidate was selected outside it: between
     * the two, a frame may have arrived and finished the task, and failing it then would overwrite
     * a real result with an invented one.
     */
    private suspend fun failAsTimedOut(uuid: String, now: Long, sqlClient: SqlClient): Boolean {
        val task = databaseManager.serverTaskDao.getByUuid(uuid, sqlClient) ?: return true

        if (!ServerTaskTimeout.hasTimedOut(task.status, task.updatedAt, now)) {
            return task.status.isTerminal
        }

        logger.warn(
            "Task ${task.uuid} (${task.kind}) on node ${task.nodeId} was ${task.status} with no progress for " +
                "${(now - task.updatedAt) / 1000}s, failing it."
        )

        failUnfinished(task, ServerTaskTimeout.TIMEOUT_ERROR, now, sqlClient)

        return true
    }

    /**
     * Fails the RUNNING tasks of [nodeId] that the node, in its hello, no longer lists as running
     * ([ServerTaskAbandonment]).
     *
     * A daemon that restarted -- an update, a crash, a service restart -- runs none of the tasks
     * its previous process had going, and one too old or too abruptly killed to report them failed
     * leaves rows RUNNING that only the ten-minute sweep would ever close. Each candidate is
     * re-read and re-judged under its lock, like the sweep, so a frame that finished it meanwhile
     * wins. Null [runningTasks] (a node too old to list them) changes nothing.
     */
    suspend fun failAbandonedByNode(nodeId: Long, runningTasks: Collection<String?>?, helloAt: Long) {
        val running = runningTasks?.filterNotNull()?.toSet() ?: return

        val sqlClient = databaseManager.getSqlClient()

        databaseManager.serverTaskDao.getAllUnfinished(sqlClient)
            .filter { ServerTaskAbandonment.isAbandoned(it, nodeId, running, helloAt) }
            .forEach { candidate ->
                withTaskLock(candidate.uuid) {
                    val task = databaseManager.serverTaskDao.getByUuid(candidate.uuid, sqlClient) ?: return@withTaskLock true

                    if (!ServerTaskAbandonment.isAbandoned(task, nodeId, running, helloAt)) {
                        return@withTaskLock task.status.isTerminal
                    }

                    logger.warn(
                        "Task ${task.uuid} (${task.kind}) was RUNNING on node $nodeId, which no longer runs it " +
                            "after reconnecting; failing it."
                    )

                    failUnfinished(task, ServerTaskAbandonment.NODE_RESTARTED_ERROR, System.currentTimeMillis(), sqlClient)

                    true
                }
            }
    }

    /**
     * Ends [task] as FAILED with [error] the way a FAILED frame from the node would have: the row,
     * the panel, a waiting request, the server it was about and a plugin install's provenance.
     * Shared by every path where Pano gives a task up on the node's behalf. Under the task's lock.
     */
    private suspend fun failUnfinished(task: ServerTask, error: String, now: Long, sqlClient: SqlClient) {
        task.status = ServerTaskStatus.FAILED
        task.error = error
        task.updatedAt = now

        databaseManager.serverTaskDao.updateProgressByUuid(
            uuid = task.uuid,
            status = task.status,
            percent = task.percent,
            message = task.message,
            error = task.error,
            updatedAt = task.updatedAt,
            sqlClient = sqlClient
        )

        panelRealtimeHub.pushTaskProgress(task)

        completeTerminal(task.uuid, TaskOutcome(task.status, task.error))

        takeStartAfter(task.uuid)
        takeStartCarriedByLink(task.uuid)

        onTaskFailed(task.kind, task.serverId, task.error, sqlClient, task.uuid)

        // A plugin download given up on settles its provenance row like a failed one, as the
        // progress handler does for a FAILED frame: otherwise the row keeps the task id for good
        // and the plugin is never checked for updates again.
        if (task.kind == ServerTaskKind.PLUGIN_INSTALL) {
            managedServerPluginService.ifAvailable?.onInstallTaskFinished(task.uuid, task.serverId, task.status, sqlClient)
            panoPluginUpdateService.ifAvailable?.onNodeTaskFinished(task.uuid, task.serverId, task.status, sqlClient)
        }

        task.nodeId?.let(::onNodeTaskEnded)
    }

    /**
     * A task of [nodeId] ended, by a frame or by Pano giving it up: the node's automatic update
     * may have been waiting for exactly that ([NodeDaemonUpdateService.onNodeTaskFinished]).
     */
    fun onNodeTaskEnded(nodeId: Long) {
        nodeDaemonUpdateService.ifAvailable?.onNodeTaskFinished(nodeId)
    }

    /**
     * Fails the unfinished installs of [done]'s server that [done] replaced
     * ([ServerInstallFailure.staleBefore]), each under its own lock, and without touching the
     * server: [done] is what the server is now.
     */
    suspend fun failSupersededInstalls(done: ServerTask, sqlClient: SqlClient) {
        val stale = ServerInstallFailure.staleBefore(done, databaseManager.serverTaskDao.getAllUnfinished(sqlClient))

        stale.forEach { candidate ->
            withTaskLock(candidate.uuid) {
                val task = databaseManager.serverTaskDao.getByUuid(candidate.uuid, sqlClient) ?: return@withTaskLock true

                if (task.status.isTerminal) {
                    return@withTaskLock true
                }

                logger.info("Task ${task.uuid} (${task.kind}) of server ${task.serverId} was replaced by ${done.uuid}, failing it.")

                task.status = ServerTaskStatus.FAILED
                task.error = ServerInstallFailure.SUPERSEDED_ERROR
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

                panelRealtimeHub.pushTaskProgress(task)

                completeTerminal(task.uuid, TaskOutcome(task.status, task.error))

                takeStartAfter(task.uuid)
                takeStartCarriedByLink(task.uuid)

                true
            }
        }
    }

    /**
     * What a failed task of [kind] means for the server it belonged to.
     *
     * Called from the progress handler and from the sweep alike: whether a node said a task failed
     * or simply stopped saying anything, the row it was working on has to end in a state somebody
     * can act on.
     */
    suspend fun onTaskFailed(
        kind: ServerTaskKind,
        serverId: Long?,
        error: String?,
        sqlClient: SqlClient,
        taskUuid: String? = null
    ) {
        val id = serverId ?: return

        // A backup that never finished leaves a CREATING row nothing would ever close. There is
        // no backup id on the task, and there does not need to be: a node runs one backup of a
        // server at a time, so the unfinished ones of that server are the ones this failed.
        if (kind == ServerTaskKind.BACKUP) {
            databaseManager.serverBackupDao.failCreatingByServerId(id, sqlClient)

            panelRealtimeHub.pushServerBackupsChanged(id)

            // A backup that did not happen is the one failure here nobody finds out about on
            // their own: there is no error on a screen, only a copy that is not there next week.
            databaseManager.serverDao.getById(id, sqlClient)?.let { server ->
                alertManager.onBackupFailed(server, error, sqlClient)
            }
        }

        // An install that failed leaves a row that can never start. It is marked STOPPED rather
        // than left INSTALLING forever, and keeps the reason, so the panel can show it and offer a
        // reinstall instead of a spinner or a Start button that does nothing.
        if (kind == ServerTaskKind.IMPORT && taskUuid != null && removeOnFailure.remove(taskUuid)) {
            logger.info("Import task $taskUuid failed (${error.orEmpty()}); removing server $id it was creating.")

            serverRemovalService.getObject().remove(id, sqlClient)

            return
        }

        if (kind == ServerTaskKind.INSTALL || kind == ServerTaskKind.REINSTALL || kind == ServerTaskKind.IMPORT) {
            // An install a newer one replaced failing late (the sweep, a dead node) says nothing
            // about the server: the newer one's outcome is on the row, and STOPPED written over it
            // would be a lie about a server that may well be running.
            if (taskUuid != null && isSupersededInstall(taskUuid, id, sqlClient)) {
                return
            }

            databaseManager.serverDao.getById(id, sqlClient)?.let { server ->
                val installError = ServerInstallFailure.afterFailure(kind, server.installError, error)

                if (installError != server.installError) {
                    databaseManager.serverDao.updateInstallErrorById(id, installError, sqlClient)
                }
            }

            databaseManager.serverDao.updateProcessStateById(id, ServerProcessState.STOPPED, null, sqlClient)

            panelRealtimeHub.pushServerState(id, ServerProcessState.STOPPED.name, null, null, null)
            panelRealtimeHub.notifyServerUpdated(id)
        }
    }

    private suspend fun isSupersededInstall(taskUuid: String, serverId: Long, sqlClient: SqlClient): Boolean {
        val tasks = databaseManager.serverTaskDao.getAllByServerId(serverId, sqlClient)
        val task = tasks.firstOrNull { it.uuid == taskUuid } ?: return false

        return ServerInstallFailure.isSuperseded(task, tasks)
    }

    companion object {
        /** Once a minute: the deadlines are minutes long, so finer resolution buys nothing. */
        private const val SWEEP_INTERVAL_MS = 60_000L
    }
}
