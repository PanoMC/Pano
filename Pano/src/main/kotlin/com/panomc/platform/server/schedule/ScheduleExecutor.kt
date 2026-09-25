package com.panomc.platform.server.schedule

import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerScheduleTask
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.SendCommandMessage
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.ServerPowerAction
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.server.backup.BackupOptions
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeature
import io.vertx.core.json.JsonObject
import com.panomc.platform.server.message.ExecuteCommandMessage
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID
import com.panomc.platform.node.ServerInstallFailure
import com.panomc.platform.node.message.PowerMessage as NodePowerMessage
import com.panomc.platform.server.message.PowerMessage as PluginPowerMessage

/**
 * Runs a schedule's steps against one server, right now.
 *
 * This is the path for "run it now" on any server and for every run of a linked server's
 * schedules; a managed server's timed runs happen on its node instead, where they survive Pano
 * restarting. The two implementations are deliberately the same sequence of the same three
 * actions, so a schedule tested with the Run button behaves the way it will at four in the
 * morning.
 *
 * Steps run in order and stop at the first failure. That is the point of an ordered list: "back
 * up, then restart" must not restart when the backup failed, because the backup is the only
 * reason the restart was made safe.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ScheduleExecutor(
    private val serverManager: ServerManager,
    private val nodeManager: NodeManager,
    private val fileClient: ManagedServerFileClient,
    private val backupService: ManagedServerBackupService
) {
    /** What a run did, as the caller records it. */
    data class Outcome(val status: ScheduleRunStatus, val error: String? = null)

    /**
     * Runs every step of [tasks] against [server].
     *
     * A power step is always last in effect even when it is not last in the list — it takes the
     * server down with it — so nothing after it is attempted.
     */
    suspend fun run(
        server: Server,
        scheduleName: String,
        tasks: List<ServerScheduleTask>,
        issuedBy: Long?,
        sqlClient: SqlClient
    ): Outcome {
        if (tasks.isEmpty()) {
            return Outcome(ScheduleRunStatus.OK)
        }

        val issuer = issuerFor(scheduleName)

        tasks.sortedBy { it.position }.forEach { task ->
            val failure = try {
                runTask(server, task, issuer, issuedBy, sqlClient)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }

            if (failure != null) {
                return Outcome(
                    if (failure == OFFLINE) ScheduleRunStatus.SKIPPED else ScheduleRunStatus.FAILED,
                    failure
                )
            }
        }

        return Outcome(ScheduleRunStatus.OK)
    }

    /** Sends one countdown line to the server's console. Best effort; a warning is not a run. */
    fun warn(server: Server, minutes: Int, restarting: Boolean) {
        sendCommand(server, ScheduleWarnings.message(minutes, restarting), issuerFor(WARNING_ISSUER))
    }

    /** Whether this server can be acted on at all right now. */
    fun isReachable(server: Server): Boolean = if (server.isManaged) {
        server.nodeId?.let { nodeManager.isConnected(it) } == true
    } else {
        serverManager.isConnected(server.id)
    }

    /** Returns null on success, or the reason it failed. */
    private suspend fun runTask(
        server: Server,
        task: ServerScheduleTask,
        issuer: String,
        issuedBy: Long?,
        sqlClient: SqlClient
    ): String? {
        val payload = task.payloadObject()

        return when (task.kind) {
            ScheduleTaskKind.COMMAND -> {
                val command = payload.getString("command").orEmpty()

                if (command.isBlank()) {
                    "The command step had no command."
                } else if (!sendCommand(server, command, issuer)) {
                    OFFLINE
                } else {
                    null
                }
            }

            ScheduleTaskKind.POWER -> {
                val action = ServerPowerAction.fromId(payload.getString("action"))

                if (action != ServerPowerAction.STOP && action != ServerPowerAction.RESTART) {
                    "\"${payload.getString("action")}\" is not something a schedule may do."
                } else if (server.isManaged && ServerInstallFailure.blocks(action, server.installError)) {
                    INSTALL_FAILED
                } else if (!sendPower(server, action, issuer)) {
                    OFFLINE
                } else {
                    null
                }
            }

            ScheduleTaskKind.BACKUP ->
                backup(server, payload, issuedBy, sqlClient)
        }
    }

    private suspend fun backup(
        server: Server,
        payload: JsonObject,
        issuedBy: Long?,
        sqlClient: SqlClient
    ): String? {
        // Whoever can take a backup: a node, or since SM-47 a plugin with `backups`. Asking only
        // "is it managed?" refused every linked server whose plugin could do the job.
        val target = try {
            fileClient.resolve(server.id, sqlClient, ServerFeature.BACKUPS_CREATE)
        } catch (_: FeatureUnavailable) {
            return if (server.isManaged) OFFLINE else "This server has no files Pano can back up."
        } catch (_: Exception) {
            return OFFLINE
        }

        // The stored payload was validated on save; one that no longer parses (edited by hand, or
        // written by a newer Pano) fails the step rather than silently taking something else.
        val options = try {
            BackupOptions.parse(payload)
        } catch (_: Exception) {
            return "The backup step has options this Pano cannot read."
        }

        val keep = payload.getInteger("keep")

        return try {
            backupService.create(target, payload.getString("name"), issuedBy ?: SYSTEM_USER_ID, sqlClient, options)

            // Trimmed now rather than when the archive finishes: the row already exists and
            // counts, so the oldest copies go while this one is still being written.
            if (keep != null) {
                backupService.applyRetention(server, sqlClient, keep, options.mode)
            }

            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun sendCommand(server: Server, command: String, issuer: String): Boolean = if (server.isManaged) {
        val nodeId = server.nodeId
        val uuid = server.uuid

        if (nodeId == null || uuid == null) {
            false
        } else {
            nodeManager.sendMessage(nodeId, SendCommandMessage(uuid, command, issuer))
        }
    } else {
        serverManager.sendMessage(
            server.id,
            ExecuteCommandMessage(command, UUID.randomUUID().toString(), issuer)
        )
    }

    private fun sendPower(server: Server, action: ServerPowerAction, issuer: String): Boolean = if (server.isManaged) {
        val nodeId = server.nodeId
        val uuid = server.uuid

        if (nodeId == null || uuid == null) {
            false
        } else {
            nodeManager.sendMessage(
                nodeId,
                NodePowerMessage(uuid, action.name, UUID.randomUUID().toString(), issuer)
            )
        }
    } else {
        serverManager.sendMessage(
            server.id,
            PluginPowerMessage(action.name, UUID.randomUUID().toString(), issuer)
        )
    }

    /** What the console shows as the sender, so a scheduled command is never mistaken for a person. */
    private fun issuerFor(name: String) = "schedule:${name.take(MAX_ISSUER_LENGTH)}"

    companion object {
        /** Not a failure of the schedule: the server was simply not there to act on. */
        const val OFFLINE = "The server was not reachable."

        /** A restart of a server whose install failed: its node has nothing to start. */
        const val INSTALL_FAILED = "The server's install failed; reinstall it first."

        private const val WARNING_ISSUER = "warning"
        private const val MAX_ISSUER_LENGTH = 32

        /** Backups taken by a schedule nobody started are attributed to no user. */
        private const val SYSTEM_USER_ID = -1L
    }
}
