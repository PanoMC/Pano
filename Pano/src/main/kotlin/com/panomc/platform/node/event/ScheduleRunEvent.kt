package com.panomc.platform.node.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.auth.panel.log.ServerScheduleRunLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Node
import com.panomc.platform.node.NodeEvent
import com.panomc.platform.node.NodeEventResponse
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.event.request.ScheduleRunEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.schedule.CronSchedules
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.schedule.ScheduleRunStatus
import com.panomc.platform.server.schedule.ScheduleTaskKind
import org.slf4j.Logger

/**
 * A node finished running one schedule (`SCHEDULE_RUN`).
 *
 * The node is the authority on managed schedules — it is the side with the clock that fired and
 * the process that answered — so Pano only records the outcome and recomputes when the schedule
 * is next due. The recomputation is Pano's own, from the same cron and zone the node was given,
 * so the "next run" the panel shows and the run the node will actually perform are the same
 * calculation done twice rather than a number passed over the wire.
 */
@Event
class ScheduleRunEvent(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val backupService: ManagedServerBackupService,
    private val alertManager: AlertManager,
    private val logger: Logger
) : NodeEvent<ScheduleRunEventRequest, NodeEventResponse>() {
    override suspend fun handle(request: ScheduleRunEventRequest, node: Node): NodeEventResponse? {
        val sqlClient = databaseManager.getSqlClient()

        val server = nodeManager.resolveServer(node, request.serverUuid, sqlClient) ?: return null

        val scheduleUuid = request.scheduleUuid ?: return null

        record(server, scheduleUuid, request)

        return null
    }

    /**
     * Records one finished run, whoever ran it.
     *
     * A plugin that announced the `schedules` capability keeps the clock for its own server and
     * reports the same `SCHEDULE_RUN` frame (§2.4.17 C), so the bookkeeping is one
     * implementation and the caller is only responsible for proving whose server it is.
     */
    suspend fun record(
        server: com.panomc.platform.db.model.Server,
        scheduleUuid: String,
        request: ScheduleRunEventRequest
    ) {
        val sqlClient = databaseManager.getSqlClient()

        val schedule = databaseManager.serverScheduleDao.getByUuid(scheduleUuid, sqlClient) ?: return

        // A node may only report on schedules of the server it named, which is already proven to
        // be its own.
        if (schedule.serverId != server.id) {
            logger.warn("A schedule run was reported for a server that does not own it, ignoring.")

            return
        }

        val finishedAt = request.finishedAt ?: System.currentTimeMillis()
        val ok = request.ok == true

        val status = if (ok) ScheduleRunStatus.OK else ScheduleRunStatus.FAILED
        val error = request.error?.take(MAX_ERROR_LENGTH)

        databaseManager.serverScheduleDao.updateRunResultById(
            id = schedule.id,
            lastRunAt = request.startedAt ?: finishedAt,
            lastStatus = status,
            lastError = error,
            nextRunAt = CronSchedules.nextRun(schedule.cron, schedule.timezone, finishedAt),
            sqlClient = sqlClient
        )

        databaseManager.panelActivityLogDao.add(
            ServerScheduleRunLog(server.id, schedule.name, ok, error),
            sqlClient
        )

        if (ok) {
            applyScheduleRetention(server, schedule.id, sqlClient)
        } else {
            alertManager.onScheduleFailed(server, schedule.name, error, sqlClient)
        }

        panelRealtimeHub.pushScheduleRun(server.id, schedule.id, ok, error)
        panelRealtimeHub.pushServerSchedulesChanged(server.id)
    }

    /**
     * Trims backups to a schedule's own `keep`, when it asked for one.
     *
     * The node takes the archive but knows nothing about retention — only Pano holds the rows and
     * only Pano knows how many copies this particular schedule is supposed to leave behind. A
     * schedule without a `keep` falls through to the server's own setting, applied when the
     * `BACKUP_CREATED` for that archive arrives.
     */
    private suspend fun applyScheduleRetention(
        server: com.panomc.platform.db.model.Server,
        scheduleId: Long,
        sqlClient: io.vertx.sqlclient.SqlClient
    ) {
        val payload = databaseManager.serverScheduleTaskDao
            .getAllByScheduleId(scheduleId, sqlClient)
            .firstOrNull { it.kind == ScheduleTaskKind.BACKUP }
            ?.payloadObject()
            ?: return

        val keep = payload.getInteger("keep") ?: return

        // The override is the schedule's own kind of backup's limit and no other: a snapshot job
        // keeping three must not prune the full zips down to three as well.
        val mode = BackupMode.fromId(payload.getString("mode")) ?: BackupMode.FULL

        backupService.applyRetention(server, sqlClient, keep, mode)
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 1000
    }
}
