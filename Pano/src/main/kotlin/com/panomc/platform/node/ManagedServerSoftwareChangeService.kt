package com.panomc.platform.node

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.BadRequest
import com.panomc.platform.node.message.PowerMessage
import com.panomc.platform.node.message.ReinstallKeepSpec
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerPowerAction
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.backup.BackupOptions
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.software.ServerSoftwareCatalog
import com.panomc.platform.server.software.ServerSoftwareFamily
import com.panomc.platform.server.software.SoftwareChangeKeep
import com.panomc.platform.server.software.dto.SoftwareResolution
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a reinstall — the same software again, or a change to another one — as one task (SM-66,
 * §2.4.31).
 *
 * The request only decides and validates; this service opens the REINSTALL task, answers with it,
 * and runs [SoftwareChangeSteps] in the background: stop and wait, back up and wait, hand the node
 * the reinstall. The server row takes the new software the moment the node has the job, and gets
 * the old one back if the node reports the reinstall FAILED — the node restores the old directory
 * in that case, so the row has to say what is on disk again. A server that was running when a
 * change failed is started again, since a failed change must leave it as it was.
 *
 * Everything that has to outlive the request lives in memory. A Pano restart in the middle of a
 * change leaves the task to the node's own frames (or the timeout sweep) and the start-afterwards
 * to the row's auto-start, which is exactly what a reinstall did before this ticket.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerSoftwareChangeService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val managedServerInstallService: ManagedServerInstallService,
    private val managedServerFileClient: ManagedServerFileClient,
    private val backupService: ManagedServerBackupService,
    private val serverTaskService: ServerTaskService,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverSoftwareCatalog: ServerSoftwareCatalog,
    private val vertx: Vertx,
    private val logger: Logger
) {
    /**
     * Where a change of [Target.server] would go, and what it could carry: shared by the preview
     * and the reinstall itself so the panel is never offered a checkbox the request then refuses.
     */
    data class Target(
        val server: Server,
        val software: String,
        val version: String,
        val serverType: ServerType,
        val from: ServerSoftwareFamily,
        val to: ServerSoftwareFamily,
        val allowed: SoftwareChangeKeep,
        val defaults: SoftwareChangeKeep,
        /** Why each switch [allowed] refuses is refused (see [SoftwareChangeKeep.reasons]). */
        val reasons: Map<String, String?>,
        /** The target's catalog resolution, when the catalog could resolve it; carries its Java. */
        val resolution: SoftwareResolution?
    )

    /**
     * Resolves what [server] would change to. [software] and [version] are the request's, either
     * may be null ("keep the current one" / "the recommended release"). Throws [BadRequest] for a
     * software Pano does not install or a version it cannot name.
     */
    suspend fun resolveTarget(server: Server, node: Node, software: String?, version: String?): Target {
        val targetSoftware = software?.takeIf { it.isNotBlank() }?.lowercase() ?: server.software ?: throw BadRequest()

        val serverType = serverSoftwareCatalog.serverTypeOf(targetSoftware) ?: throw BadRequest()

        val targetVersion = SoftwareChangeSteps.targetVersion(
            requestedVersion = version,
            targetSoftware = targetSoftware,
            currentSoftware = server.software,
            currentVersion = server.softwareVersion
        ) { serverSoftwareCatalog.recommendedVersion(it) } ?: throw BadRequest()

        val from = ServerSoftwareFamily.of(server.software, server.type)
        val to = ServerSoftwareFamily.of(targetSoftware, serverType)

        val nodeKeepsEverything = NodeProtocol.supportsReinstallKeep(node.protocolVersion)

        return Target(
            server = server,
            software = targetSoftware,
            version = targetVersion,
            serverType = serverType,
            from = from,
            to = to,
            allowed = SoftwareChangeKeep.allowed(from, to, nodeKeepsEverything),
            defaults = SoftwareChangeKeep.defaults(from, to, nodeKeepsEverything),
            reasons = SoftwareChangeKeep.reasons(from, to, nodeKeepsEverything),
            resolution = serverSoftwareCatalog.resolve(targetSoftware, targetVersion)
        )
    }

    /** Everything the request decided. */
    data class Request(
        val server: Server,
        val node: Node,
        val userId: Long,
        val software: String,
        val version: String,
        val serverType: ServerType,
        val keep: SoftwareChangeKeep,
        val backupFirst: Boolean,
        val startAfter: Boolean,
        /** Whether the server was up when the change was asked for; decides the stop step. */
        val running: Boolean
    )

    /** Servers with a change in flight; a second one while the first runs is refused. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Opens the REINSTALL task, starts the steps in the background and returns the task.
     *
     * Throws [BadRequest] when a change of this server is already running.
     */
    suspend fun begin(request: Request, sqlClient: SqlClient): ServerTask {
        val serverId = request.server.id

        if (!inFlight.add(serverId)) {
            throw BadRequest()
        }

        val task = try {
            openTask(request, sqlClient)
        } catch (exception: Exception) {
            inFlight.remove(serverId)

            throw exception
        }

        serverTaskService.setStartAfter(task.uuid, request.startAfter)

        panelRealtimeHub.pushTaskProgress(task)

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                run(request, task)
            } catch (exception: Exception) {
                logger.warn("Software change of server $serverId failed: ${exception.message}")

                failTask(task.uuid, exception.message ?: exception.javaClass.simpleName, request)
            } finally {
                inFlight.remove(serverId)
            }
        }

        return task
    }

    private suspend fun openTask(request: Request, sqlClient: SqlClient): ServerTask {
        val now = System.currentTimeMillis()

        // RUNNING from the start: the stop and the backup are work in progress, and a PENDING row
        // would be failed by the sweep after two minutes of a backup nobody reports on.
        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = request.server.id,
            nodeId = request.node.id,
            kind = ServerTaskKind.REINSTALL,
            status = ServerTaskStatus.RUNNING,
            percent = 0,
            message = MESSAGE_PREPARING,
            createdBy = request.userId,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverTaskDao.add(task, sqlClient)

        return databaseManager.serverTaskDao.getById(id, sqlClient) ?: task
    }

    private suspend fun run(request: Request, task: ServerTask) {
        val oldSoftware = request.server.software
        val oldVersion = request.server.softwareVersion

        val plan = SoftwareChangeSteps.Plan(
            running = request.running,
            backupFirst = request.backupFirst,
            backupName = SoftwareChangeSteps.backupName(
                oldSoftware,
                oldVersion,
                request.software,
                request.version,
                System.currentTimeMillis()
            )
        )

        val outcome = SoftwareChangeSteps(StepActions(request, task)).run(plan)

        if (outcome != SoftwareChangeSteps.Outcome.HANDED_TO_NODE) {
            // Stopped by this change and not changed after all: bring it back as it was.
            if (request.running && outcome != SoftwareChangeSteps.Outcome.STOP_FAILED) {
                sendPower(request, ServerPowerAction.START)
            }

            return
        }

        awaitNode(request, task, oldSoftware, oldVersion)
    }

    /**
     * Waits for the node's end of the reinstall and undoes the row's software on a failure.
     *
     * The waiter is registered before the message is sent (in [StepActions.reinstall]), so an
     * install that fails within milliseconds is not missed.
     */
    private suspend fun awaitNode(request: Request, task: ServerTask, oldSoftware: String?, oldVersion: String?) {
        val outcome = serverTaskService.expectTerminal(task.uuid).await()

        if (outcome.status != ServerTaskStatus.FAILED) {
            return
        }

        val sqlClient = databaseManager.getSqlClient()
        val server = databaseManager.serverDao.getById(request.server.id, sqlClient) ?: return

        if (server.software != oldSoftware || server.softwareVersion != oldVersion) {
            databaseManager.serverDao.updateSoftwareById(server.id, oldSoftware, oldVersion, sqlClient)

            server.software = oldSoftware
            server.softwareVersion = oldVersion
            server.type = request.server.type
            server.version = request.server.version

            databaseManager.serverDao.update(server, sqlClient)

            panelRealtimeHub.notifyServerUpdated(server.id)
        }

        if (request.running) {
            sendPower(request, ServerPowerAction.START)
        }
    }

    private inner class StepActions(
        private val request: Request,
        private val task: ServerTask
    ) : SoftwareChangeSteps.Actions {
        override suspend fun progress(message: String) {
            updateTask(task.uuid, message)
        }

        override suspend fun stopAndWait(): Boolean {
            if (!sendPower(request, ServerPowerAction.STOP)) {
                return false
            }

            val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)

                val state = databaseManager.serverDao.getById(request.server.id, databaseManager.getSqlClient())
                    ?.processState

                if (state == null || !state.isAlive) {
                    return true
                }
            }

            return false
        }

        override suspend fun backup(name: String): String? {
            val sqlClient = databaseManager.getSqlClient()

            val target = managedServerFileClient.resolve(request.server.id, sqlClient, ServerFeature.BACKUPS_CREATE)

            val (_, backupTask) = backupService.create(target, name, request.userId, sqlClient, BackupOptions())

            var lastPercent = -1
            var lastTouch = System.currentTimeMillis()

            while (true) {
                delay(POLL_INTERVAL_MS)

                val current = databaseManager.serverTaskDao.getByUuid(backupTask.uuid, databaseManager.getSqlClient())
                    ?: return "the backup task disappeared"

                when (current.status) {
                    ServerTaskStatus.DONE -> return null
                    ServerTaskStatus.FAILED -> return current.error ?: current.message ?: "unknown error"
                    else -> Unit
                }

                val now = System.currentTimeMillis()

                // Mirrored onto the change's own task, and touched at least every half minute: a
                // backup of a big world can run past the ten minutes the sweep allows a silent task.
                if (current.percent != lastPercent || now - lastTouch >= TOUCH_INTERVAL_MS) {
                    lastPercent = current.percent
                    lastTouch = now

                    updateTask(task.uuid, "Backing up the server ($name, ${current.percent}%)")
                }
            }
        }

        override suspend fun reinstall() {
            val sqlClient = databaseManager.getSqlClient()

            // Re-read: the stop and the backup took a while, and the row moved on meanwhile.
            val server = databaseManager.serverDao.getById(request.server.id, sqlClient)
                ?: throw IllegalStateException("The server no longer exists.")

            val changed = request.software != server.software || request.version != server.softwareVersion

            server.software = request.software
            server.softwareVersion = request.version
            server.type = request.serverType
            server.version = request.version

            val current = databaseManager.serverTaskDao.getByUuid(task.uuid, sqlClient) ?: task

            // Before the send, so a node that fails at once cannot finish the task unseen.
            serverTaskService.expectTerminal(task.uuid)

            try {
                managedServerInstallService.start(
                    server = server,
                    node = request.node,
                    kind = ServerTaskKind.REINSTALL,
                    createdBy = request.userId,
                    acceptEula = true,
                    sqlClient = sqlClient,
                    keep = request.keep.let { ReinstallKeepSpec(it.worlds, it.plugins, it.configs) },
                    existingTask = current
                )
            } catch (exception: Exception) {
                serverTaskService.forgetTerminal(task.uuid)

                throw exception
            }

            // Only once the node has the job: a change that never reached it leaves the row alone.
            if (changed) {
                databaseManager.serverDao.updateSoftwareById(server.id, request.software, request.version, sqlClient)
                databaseManager.serverDao.update(server, sqlClient)

                panelRealtimeHub.notifyServerUpdated(server.id)
            }
        }

        override suspend fun fail(error: String) {
            failTask(task.uuid, error, request)
        }
    }

    private suspend fun updateTask(uuid: String, message: String) {
        serverTaskService.withTaskLock(uuid) {
            val sqlClient = databaseManager.getSqlClient()
            val task = databaseManager.serverTaskDao.getByUuid(uuid, sqlClient) ?: return@withTaskLock true

            if (task.status.isTerminal) {
                return@withTaskLock true
            }

            task.message = message
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

            false
        }
    }

    /**
     * Ends the change's task as FAILED. Deliberately not [ServerTaskService.onTaskFailed]: that
     * marks the server STOPPED as if an install had died half-way, and a change that failed before
     * the node got it has not touched the server at all.
     */
    private suspend fun failTask(uuid: String, error: String, request: Request) {
        serverTaskService.takeStartAfter(uuid)

        serverTaskService.withTaskLock(uuid) {
            val sqlClient = databaseManager.getSqlClient()
            val task = databaseManager.serverTaskDao.getByUuid(uuid, sqlClient) ?: return@withTaskLock true

            if (task.status.isTerminal) {
                return@withTaskLock true
            }

            task.status = ServerTaskStatus.FAILED
            task.error = error.take(MAX_ERROR_LENGTH)
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

            serverTaskService.completeTerminal(task.uuid, ServerTaskService.TaskOutcome(task.status, task.error))

            true
        }

        logger.info("Software change of server ${request.server.id} ended before the node got it: $error")
    }

    private fun sendPower(request: Request, action: ServerPowerAction): Boolean {
        val uuid = request.server.uuid ?: return false

        return nodeManager.sendMessage(
            request.node.id,
            PowerMessage(
                serverUuid = uuid,
                action = action.name,
                requestId = UUID.randomUUID().toString(),
                issuedBy = ISSUER
            )
        )
    }

    companion object {
        const val MESSAGE_PREPARING = "Preparing the change"

        /** How long a graceful stop may take before the change gives up, untouched. */
        const val STOP_TIMEOUT_MS = 3 * 60 * 1000L

        private const val POLL_INTERVAL_MS = 1000L
        private const val TOUCH_INTERVAL_MS = 30_000L
        private const val MAX_ERROR_LENGTH = 2000

        /** Shown in the server's console as whoever sent the stop and the start. */
        const val ISSUER = "software-change"
    }
}
