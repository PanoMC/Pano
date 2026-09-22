package com.panomc.platform.server.backup

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerBackup
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.message.BackupCreateMessage
import com.panomc.platform.node.message.BackupDeleteMessage
import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.message.BackupRestoreMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureSource
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Starting, finishing and pruning managed-server backups.
 *
 * The row is written before the node is told anything, so a backup is visible while it is being
 * taken and a node that dies halfway leaves something an operator can see and delete rather than
 * an archive nobody knows about. Retention runs on Pano rather than on the node for the same
 * reason the row does: only Pano knows how many copies this server is supposed to keep, and only
 * Pano can delete the row that goes with the file.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerBackupService(
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val logger: Logger
) {
    /**
     * Starts a backup and returns the row and the task tracking it.
     *
     * Throws [NodeOffline] when the message could not be handed over, having already removed the
     * row it wrote — a backup that was never started should not appear in the list as one that
     * failed.
     */
    suspend fun create(
        target: ManagedServerFileClient.Target,
        name: String?,
        createdBy: Long,
        sqlClient: SqlClient,
        options: BackupOptions = BackupOptions()
    ): Pair<ServerBackup, ServerTask> {
        val now = System.currentTimeMillis()
        val backupId = UUID.randomUUID().toString()

        val exclude = options.effectiveExclude()

        val backup = ServerBackup(
            uuid = backupId,
            serverId = target.server.id,
            nodeId = target.nodeId,
            name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH) ?: defaultName(now),
            status = ServerBackupStatus.CREATING,
            createdBy = createdBy,
            createdAt = now,
            // What was asked for; BACKUP_CREATED overwrites mode and scope with what was made.
            mode = options.mode,
            scope = options.scope,
            include = options.include,
            exclude = exclude
        )

        val backupRowId = databaseManager.serverBackupDao.add(backup, sqlClient)

        val stored = databaseManager.serverBackupDao.getById(backupRowId, sqlClient) ?: backup

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = target.server.id,
            nodeId = target.nodeId,
            kind = ServerTaskKind.BACKUP,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = "Backing up ${stored.name}",
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val storedTask = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task

        val sent = trySend(
            target,
            BackupCreateMessage(
                serverUuid = target.serverUuid,
                taskId = task.uuid,
                backupId = backupId,
                name = stored.name,
                exclude = exclude,
                mode = options.mode.name,
                scope = options.scope.name,
                include = options.include.takeIf { options.scope == BackupScope.CUSTOM }
            )
        )

        if (!sent) {
            databaseManager.serverBackupDao.deleteById(backupRowId, sqlClient)

            throw NodeOffline()
        }

        panelRealtimeHub.pushTaskProgress(storedTask)
        panelRealtimeHub.pushServerBackupsChanged(target.server.id)

        return stored to storedTask
    }

    /** Asks a node to put a backup back, and returns the task that follows it. */
    suspend fun restore(
        target: ManagedServerFileClient.Target,
        backup: ServerBackup,
        createdBy: Long,
        sqlClient: SqlClient
    ): ServerTask {
        val now = System.currentTimeMillis()

        val task = ServerTask(
            uuid = UUID.randomUUID().toString(),
            serverId = target.server.id,
            nodeId = target.nodeId,
            kind = ServerTaskKind.RESTORE,
            status = ServerTaskStatus.PENDING,
            percent = 0,
            message = "Restoring ${backup.name}",
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val taskId = databaseManager.serverTaskDao.add(task, sqlClient)

        val stored = databaseManager.serverTaskDao.getById(taskId, sqlClient) ?: task

        val message = BackupRestoreMessage(
            serverUuid = target.serverUuid,
            backupId = backup.uuid,
            taskId = task.uuid
        )

        // A node puts the files back there and then and reports through the task. A plugin cannot
        // -- it is running inside the very server whose files would be overwritten -- so it writes
        // a marker, answers `next-start`, and the task waits in PENDING_RESTART until the server
        // comes back up and says how it went (§2.4.17 C).
        if (target.source == ServerFeatureSource.PLUGIN) {
            val payload = fileClient.request(target, message)

            if (payload.getString("mode") == MODE_NEXT_START) {
                databaseManager.serverTaskDao.updateProgressByUuid(
                    uuid = stored.uuid,
                    status = ServerTaskStatus.PENDING_RESTART,
                    percent = stored.percent,
                    message = "Restoring ${backup.name} on the next start",
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                    sqlClient = sqlClient
                )

                stored.status = ServerTaskStatus.PENDING_RESTART
                stored.message = "Restoring ${backup.name} on the next start"
            }
        } else if (!trySend(target, message)) {
            throw NodeOffline()
        }

        panelRealtimeHub.pushTaskProgress(stored)

        return stored
    }

    /** Hands [message] to whichever side owns this server, reporting whether it went out. */
    private fun trySend(target: ManagedServerFileClient.Target, message: NodeMessage): Boolean = try {
        fileClient.send(target, message)

        true
    } catch (e: Exception) {
        logger.warn("Could not reach server ${target.server.id} to ${message.getResponseName()}: ${e.message}")

        false
    }

    /**
     * Records a backup the node finished, and prunes whatever retention says is now surplus.
     *
     * The row may be gone — a server deleted while its backup was running — in which case there is
     * nothing to record and nothing to prune.
     */
    suspend fun onCreated(server: Server, report: CreatedReport, sqlClient: SqlClient) {
        val backupId = report.backupId

        val backup = databaseManager.serverBackupDao.getByUuid(backupId, sqlClient)
            ?: return onUnrequestedCreated(server, report, sqlClient)

        if (backup.serverId != server.id) {
            logger.warn("A node reported backup $backupId for a server it does not belong to, ignoring.")

            return
        }

        databaseManager.serverBackupDao.updateResultByUuid(
            uuid = backupId,
            sizeBytes = report.sizeBytes,
            sha256 = report.sha256,
            status = ServerBackupStatus.READY,
            mode = report.mode,
            scope = report.scope,
            fileCount = report.fileCount,
            storedBytes = report.storedBytesOrEstimate(),
            sqlClient = sqlClient
        )

        applyRetention(server, sqlClient)

        panelRealtimeHub.pushServerBackupsChanged(server.id)
    }

    /**
     * Records a backup Pano never asked for.
     *
     * A schedule running on the node takes its own backups: it invents the id, because it is the
     * side with the clock, and Pano first hears about the archive when it already exists. Writing
     * the row here is what makes a scheduled backup listable, downloadable and subject to the same
     * retention as one somebody pressed a button for — without it the disk would fill with
     * archives nothing in the panel could see.
     *
     * [source] is who reported it, and it decides the row's node: a node's backup lives on that
     * node, while one a plugin took lives in the server's own `backups/` directory and has no node
     * at all. This used to return early for a server without a node id, which is exactly the
     * server whose plugin runs the schedules — so every scheduled backup such a plugin took was an
     * archive on disk the panel never listed and retention never pruned.
     */
    private suspend fun onUnrequestedCreated(server: Server, report: CreatedReport, sqlClient: SqlClient) {
        val nodeId = when (report.source) {
            ServerFeatureSource.NODE -> server.nodeId ?: return
            else -> null
        }

        val now = report.createdAt ?: System.currentTimeMillis()

        databaseManager.serverBackupDao.add(
            ServerBackup(
                uuid = report.backupId,
                serverId = server.id,
                nodeId = nodeId,
                name = report.name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH) ?: defaultName(now),
                sizeBytes = report.sizeBytes,
                sha256 = report.sha256,
                status = ServerBackupStatus.READY,
                createdBy = SCHEDULE_USER_ID,
                createdAt = now,
                mode = report.mode,
                scope = report.scope,
                fileCount = report.fileCount,
                storedBytes = report.storedBytesOrEstimate(),
                include = report.include,
                exclude = report.exclude
            ),
            sqlClient
        )

        applyRetention(server, sqlClient)

        panelRealtimeHub.pushServerBackupsChanged(server.id)
    }

    /**
     * Deletes whatever is over this server's keep-last, oldest first.
     *
     * The node is asked first and the row only goes once it agrees: a row removed while the
     * archive survives is a file that can never be reached or deleted again, which is how a disk
     * fills up with backups nobody can see.
     *
     * [keepOverride] lets a schedule keep a different number than the server does, so a nightly
     * job and a weekly one can each hold the history that makes sense for them. It replaces the
     * limit of [overrideMode] only — the schedule's own kind of backup — and the other mode keeps
     * the server's setting, so a snapshot schedule with `keep: 3` cannot prune the full zips.
     */
    suspend fun applyRetention(
        server: Server,
        sqlClient: SqlClient,
        keepOverride: Int? = null,
        overrideMode: BackupMode = BackupMode.FULL
    ) {
        val settings = server.settings

        val keepLast = keepOverride?.takeIf { overrideMode == BackupMode.FULL } ?: settings.backupKeepLast
        val snapshotKeepLast = keepOverride?.takeIf { overrideMode == BackupMode.SNAPSHOT } ?: settings.snapshotKeepLast

        val backups = databaseManager.serverBackupDao.getAllByServerId(server.id, sqlClient)

        val surplus = BackupRetention.selectForDeletion(
            backups = backups,
            keepLast = keepLast,
            snapshotKeepLast = snapshotKeepLast,
            snapshotMaxBytes = settings.snapshotMaxBytes
        )

        surplus.forEach { backup ->
            if (delete(server, backup, sqlClient)) {
                logger.info("Retention removed backup ${backup.uuid} of server ${server.id}.")
            }
        }
    }

    /**
     * Removes one backup from the node and from Pano.
     *
     * A node that no longer has the archive is not an error: `BACKUP_DELETE` succeeding on a file
     * that is already gone and the row going with it is exactly the state everybody wanted.
     */
    suspend fun delete(server: Server, backup: ServerBackup, sqlClient: SqlClient): Boolean {
        // Whichever side holds the archive: the node the row remembers, or the plugin, which keeps
        // its archives beside the server it runs in and never had a node id to remember (§2.4.17 C).
        // `model.Error` (FeatureUnavailable, NodeOffline) extends Throwable, not Exception, so a
        // catch of Exception alone would let a refused resolve escape as the request's failure.
        val target = try {
            fileClient.resolve(server.id, sqlClient, ServerFeature.BACKUPS_CREATE)
        } catch (_: Exception) {
            null
        } catch (_: com.panomc.platform.model.Error) {
            null
        }

        if (target != null && target.nodeId == backup.nodeId) {
            try {
                fileClient.request(target, BackupDeleteMessage(target.serverUuid, backup.uuid))
            } catch (exception: Exception) {
                logger.warn("The ${target.source.id} side could not delete backup ${backup.uuid}: ${exception.message}")

                return false
            } catch (error: com.panomc.platform.model.Error) {
                logger.warn("The ${target.source.id} side could not delete backup ${backup.uuid}: ${error.message}")

                return false
            }
        } else {
            // The archive stays on a side that is not here to be told. The row would otherwise be
            // kept forever waiting for a node that may never come back, so it goes and the file is
            // left as an orphan an operator can remove from the file manager.
            logger.warn("Deleting backup ${backup.uuid} while the side holding it (node ${backup.nodeId}) is out of reach; the archive stays.")
        }

        databaseManager.serverBackupDao.deleteById(backup.id, sqlClient)

        panelRealtimeHub.pushServerBackupsChanged(server.id)

        return true
    }

    private fun defaultName(at: Long): String = "backup-$at"

    /**
     * Everything a `BACKUP_CREATED` said, from either side.
     *
     * One shape for the node's frame and the plugin's so the two cannot record the same backup
     * differently. [mode] and [scope] are already defaulted by [of]: a source that does not report
     * them is one too old to know about them, and such a source made a full zip of everything.
     */
    data class CreatedReport(
        val source: ServerFeatureSource,
        val backupId: String,
        val name: String?,
        val sizeBytes: Long,
        val sha256: String?,
        val createdAt: Long?,
        val mode: BackupMode,
        val scope: BackupScope,
        val fileCount: Long?,
        val storedBytes: Long?,
        val include: List<String>,
        val exclude: List<String>
    ) {
        /** A full zip wrote exactly its own size; an older source that did not say so still did. */
        fun storedBytesOrEstimate(): Long? = storedBytes ?: sizeBytes.takeIf { mode == BackupMode.FULL }

        companion object {
            fun of(
                source: ServerFeatureSource,
                backupId: String,
                name: String?,
                sizeBytes: Long?,
                sha256: String?,
                createdAt: Long?,
                mode: String?,
                scope: String?,
                fileCount: Long?,
                storedBytes: Long?,
                include: List<String>?,
                exclude: List<String>?
            ) = CreatedReport(
                source = source,
                backupId = backupId,
                name = name,
                sizeBytes = sizeBytes ?: 0,
                sha256 = sha256,
                createdAt = createdAt,
                mode = BackupMode.fromId(mode) ?: BackupMode.FULL,
                scope = BackupScope.fromId(scope) ?: BackupScope.ALL,
                fileCount = fileCount?.takeIf { it >= 0 },
                storedBytes = storedBytes?.takeIf { it >= 0 },
                include = include.orEmpty().take(BackupOptions.MAX_ENTRIES),
                exclude = exclude.orEmpty().take(BackupOptions.MAX_ENTRIES)
            )
        }
    }

    /** Pins or unpins one backup, taking it out of (or putting it back into) retention's reach. */
    suspend fun setPinned(server: Server, backup: ServerBackup, pinned: Boolean, sqlClient: SqlClient) {
        databaseManager.serverBackupDao.updatePinnedById(backup.id, pinned, sqlClient)

        backup.pinned = pinned

        // Unpinning does not prune on the spot even when it puts the server over a limit: the
        // click would otherwise delete the very backup somebody was looking at. It rejoins the
        // queue and goes at the next backup or settings change, like any other surplus copy.
        panelRealtimeHub.pushServerBackupsChanged(server.id)
    }

    companion object {
        const val MODE_NEXT_START = "next-start"

        const val MAX_NAME_LENGTH = 120

        /** Nobody pressed a button for this one: a schedule on the node took it. */
        const val SCHEDULE_USER_ID = -1L
    }
}
