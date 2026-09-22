package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.event.request.ScheduleRunEventRequest
import com.panomc.platform.node.event.request.TaskProgressEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.event.request.BackupRestoredEventRequest
import com.panomc.platform.server.event.request.ServerBackupCreatedEventRequest
import com.panomc.platform.server.event.request.ServerScheduleRunEventRequest
import com.panomc.platform.server.event.request.ServerTaskProgressEventRequest

/**
 * The plugin-socket twins of the node's task, backup and schedule reports (SM-47, §2.4.17 C).
 *
 * An agent-lite plugin does a node's work from inside the server and reports it with the node's
 * frames, so none of these has logic of its own: each one proves whose server the frame belongs to
 * — which the socket already did — and hands it to the same implementation the node path uses.
 * Two code paths reporting the same thing differently is exactly how the panel ends up showing two
 * kinds of backup.
 */

/**
 * `TASK_PROGRESS` from a plugin running a backup or an install.
 *
 * The bean name is spelled out because the wire name is derived from the class name, so this
 * *has* to be called `TaskProgressEvent` — and so does the node's. Two beans cannot share the
 * default name Spring would give them both.
 */
@Event("serverTaskProgressEvent")
class TaskProgressEvent(
    private val nodeTaskProgressEvent: com.panomc.platform.node.event.TaskProgressEvent
) : ServerEvent<ServerTaskProgressEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ServerTaskProgressEventRequest, server: Server): ServerEventResponse? {
        nodeTaskProgressEvent.handleFromServer(
            TaskProgressEventRequest(
                taskId = request.taskId,
                serverUuid = server.uuid,
                kind = request.kind,
                status = request.status,
                percent = request.percent,
                message = request.message,
                error = request.error
            ),
            server.id
        )

        return null
    }
}

/** `BACKUP_CREATED` from a plugin that just finished zipping its own server. */
@Event("serverBackupCreatedEvent")
class BackupCreatedEvent(
    private val databaseManager: DatabaseManager,
    private val managedServerBackupService: ManagedServerBackupService
) : ServerEvent<ServerBackupCreatedEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ServerBackupCreatedEventRequest, server: Server): ServerEventResponse? {
        val backup = request.backup ?: return null
        val backupId = backup.id ?: return null

        managedServerBackupService.onCreated(
            server = server,
            report = ManagedServerBackupService.CreatedReport.of(
                source = ServerFeatureSource.PLUGIN,
                backupId = backupId,
                name = backup.name,
                sizeBytes = backup.sizeBytes,
                sha256 = backup.sha256,
                createdAt = backup.createdAt,
                mode = backup.mode,
                scope = backup.scope,
                fileCount = backup.fileCount,
                storedBytes = backup.storedBytes,
                include = backup.include,
                exclude = backup.exclude
            ),
            sqlClient = databaseManager.getSqlClient()
        )

        return null
    }
}

/**
 * `BACKUP_RESTORED`: the end of a restore that was waiting for a restart.
 *
 * The one frame with no node equivalent, because a node never needs one — it restores while the
 * server is stopped and reports through the task like any other job. This is what closes the
 * [ServerTaskStatus.PENDING_RESTART] task the plugin's `next-start` answer opened, possibly days
 * later, and it is deliberately tolerant of a task that is already gone: an operator who cancelled
 * the restore by deleting the marker leaves nothing here to finish.
 */
@Event
class BackupRestoredEvent(
    private val databaseManager: DatabaseManager,
    private val panelRealtimeHub: PanelRealtimeHub
) : ServerEvent<BackupRestoredEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: BackupRestoredEventRequest, server: Server): ServerEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val taskId = request.taskId ?: return null
        val task = databaseManager.serverTaskDao.getByUuid(taskId, sqlClient) ?: return null

        // A plugin may only finish its own server's task, and only one that was actually left
        // waiting for this restart.
        if (task.serverId != server.id || task.status != ServerTaskStatus.PENDING_RESTART) {
            return null
        }

        val ok = request.ok == true

        task.status = if (ok) ServerTaskStatus.DONE else ServerTaskStatus.FAILED
        task.percent = if (ok) 100 else task.percent
        task.error = request.error?.take(MAX_TEXT_LENGTH)
        task.message = if (ok) "Restored on start" else "Restore failed on start"
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
        panelRealtimeHub.pushServerFilesChanged(server.id, "")

        return null
    }

    private companion object {
        private const val MAX_TEXT_LENGTH = 2000
    }
}

/** `SCHEDULE_RUN` from a plugin that keeps its own server's clock. */
@Event("serverScheduleRunEvent")
class ScheduleRunEvent(
    private val nodeScheduleRunEvent: com.panomc.platform.node.event.ScheduleRunEvent
) : ServerEvent<ServerScheduleRunEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: ServerScheduleRunEventRequest, server: Server): ServerEventResponse? {
        val scheduleUuid = request.schedule ?: return null

        nodeScheduleRunEvent.record(
            server,
            scheduleUuid,
            ScheduleRunEventRequest(
                serverUuid = server.uuid,
                scheduleUuid = scheduleUuid,
                startedAt = request.startedAt,
                finishedAt = request.finishedAt,
                ok = request.ok,
                error = request.error
            )
        )

        return null
    }
}
