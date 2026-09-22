package com.panomc.platform.server.schedule

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerSchedule
import com.panomc.platform.db.model.ServerScheduleTask
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.SyncScheduleEntry
import com.panomc.platform.node.message.SyncScheduleTaskEntry
import com.panomc.platform.node.message.SyncSchedulesMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.server.message.RelayServerMessage
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Storing schedules, and keeping every node's copy of them identical to the database's.
 *
 * Who runs a schedule depends on what kind of server it belongs to, and that split runs through
 * this whole feature. A managed server's schedules are pushed to its node and fired there, because
 * the node is the side that can still act when Pano is restarting and the side that can take a
 * backup at all. A linked server's are fired by Pano, because there is nothing else — its plugin
 * has no clock of its own and no idea what a schedule is.
 *
 * Everything that changes a schedule therefore ends with a sync, and a sync always sends the whole
 * set. See [SyncSchedulesMessage] for why that is not a wasteful choice.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerScheduleService(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val serverFeatureResolver: ServerFeatureResolver,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val alertManager: AlertManager
) {
    /** A schedule together with its steps, in order. */
    data class Detailed(val schedule: ServerSchedule, val tasks: List<ServerScheduleTask>) {
        fun toJsonObject(): JsonObject = schedule.toPublicJsonObject()
            .put("tasks", JsonArray(tasks.map { it.toPublicJsonObject() }))
    }

    /** Every schedule of one server, each with its steps. */
    suspend fun listDetailed(serverId: Long, sqlClient: SqlClient): List<Detailed> {
        val schedules = databaseManager.serverScheduleDao.getAllByServerId(serverId, sqlClient)

        if (schedules.isEmpty()) {
            return emptyList()
        }

        // One query for every schedule's tasks rather than one per schedule: a server with twenty
        // schedules should not cost twenty round trips to render one page.
        val tasks = databaseManager.serverScheduleTaskDao
            .getAllByScheduleIds(schedules.map { it.id }, sqlClient)
            .groupBy { it.scheduleId }

        return schedules.map { Detailed(it, tasks[it.id].orEmpty()) }
    }

    suspend fun getDetailed(scheduleId: Long, sqlClient: SqlClient): Detailed? {
        val schedule = databaseManager.serverScheduleDao.getById(scheduleId, sqlClient) ?: return null

        return Detailed(schedule, databaseManager.serverScheduleTaskDao.getAllByScheduleId(scheduleId, sqlClient))
    }

    /** Writes a new schedule and tells the server's node about it. */
    suspend fun create(
        server: Server,
        definition: ScheduleDefinition,
        createdBy: Long,
        sqlClient: SqlClient
    ): ServerSchedule {
        val now = System.currentTimeMillis()

        val schedule = ServerSchedule(
            uuid = UUID.randomUUID().toString(),
            serverId = server.id,
            name = definition.name,
            cron = definition.cron,
            timezone = definition.timezone,
            enabled = definition.enabled,
            warnMinutes = definition.warnMinutes,
            nextRunAt = if (definition.enabled) {
                CronSchedules.nextRun(definition.cron, definition.timezone, now)
            } else {
                null
            },
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )

        val id = databaseManager.serverScheduleDao.add(schedule, sqlClient)

        writeTasks(id, definition, sqlClient)

        val stored = databaseManager.serverScheduleDao.getById(id, sqlClient) ?: schedule

        afterChange(server, sqlClient)

        return stored
    }

    /** Replaces a schedule's definition, steps and all. */
    suspend fun update(
        server: Server,
        schedule: ServerSchedule,
        definition: ScheduleDefinition,
        sqlClient: SqlClient
    ): ServerSchedule {
        schedule.name = definition.name
        schedule.cron = definition.cron
        schedule.timezone = definition.timezone
        schedule.enabled = definition.enabled
        schedule.warnMinutes = definition.warnMinutes
        schedule.updatedAt = System.currentTimeMillis()
        schedule.nextRunAt = if (definition.enabled) {
            CronSchedules.nextRun(definition.cron, definition.timezone, schedule.updatedAt)
        } else {
            null
        }

        databaseManager.serverScheduleDao.update(schedule, sqlClient)

        // Steps are replaced rather than diffed: they are an ordered list, and reconciling one of
        // those by id is a great deal of code to arrive at the same three rows.
        databaseManager.serverScheduleTaskDao.deleteByScheduleId(schedule.id, sqlClient)

        writeTasks(schedule.id, definition, sqlClient)

        afterChange(server, sqlClient)

        return schedule
    }

    suspend fun setEnabled(server: Server, schedule: ServerSchedule, enabled: Boolean, sqlClient: SqlClient) {
        val nextRunAt = if (enabled) {
            CronSchedules.nextRun(schedule.cron, schedule.timezone, System.currentTimeMillis())
        } else {
            null
        }

        databaseManager.serverScheduleDao.updateEnabledById(schedule.id, enabled, nextRunAt, sqlClient)

        schedule.enabled = enabled
        schedule.nextRunAt = nextRunAt

        afterChange(server, sqlClient)
    }

    suspend fun delete(server: Server, schedule: ServerSchedule, sqlClient: SqlClient) {
        databaseManager.serverScheduleTaskDao.deleteByScheduleId(schedule.id, sqlClient)
        databaseManager.serverScheduleDao.deleteById(schedule.id, sqlClient)

        afterChange(server, sqlClient)
    }

    /** Records how a run Pano performed itself went, and recomputes when the next one is due. */
    suspend fun recordRun(
        server: Server,
        schedule: ServerSchedule,
        status: ScheduleRunStatus,
        error: String?,
        startedAt: Long,
        sqlClient: SqlClient
    ) {
        val finishedAt = System.currentTimeMillis()

        databaseManager.serverScheduleDao.updateRunResultById(
            id = schedule.id,
            lastRunAt = startedAt,
            lastStatus = status,
            lastError = error?.take(MAX_ERROR_LENGTH),
            nextRunAt = if (schedule.enabled) {
                CronSchedules.nextRun(schedule.cron, schedule.timezone, finishedAt)
            } else {
                null
            },
            sqlClient = sqlClient
        )

        if (status == ScheduleRunStatus.FAILED) {
            alertManager.onScheduleFailed(server, schedule.name, error, sqlClient)
        }

        panelRealtimeHub.pushScheduleRun(server.id, schedule.id, status == ScheduleRunStatus.OK, error)
        panelRealtimeHub.pushServerSchedulesChanged(server.id)
    }

    /**
     * Sends one node every schedule of every server it owns.
     *
     * Called after `NODE_HELLO`, which is the moment a node's idea of its schedules is least
     * trustworthy: it may have been offline through an edit, or it may have been restarted and
     * have none at all.
     */
    suspend fun syncNode(nodeId: Long, sqlClient: SqlClient) {
        val servers = databaseManager.serverDao.getAllByNodeId(nodeId, sqlClient)

        servers.forEach { server -> syncServer(server, sqlClient) }
    }

    /**
     * Whether a BACKUP step may be saved on [server]: wherever something can take a backup —
     * a node, or a plugin with the `backups` capability. Judged from what the server row says it
     * has as well as from what is connected this second: a managed server whose node is offline,
     * or a server whose plugin announced `backups` and is restarting, is still a server the step
     * will work on, and refusing the save over a blip would only confuse.
     */
    fun backupsAllowed(server: Server): Boolean =
        server.isManaged ||
                server.capabilities.contains(ServerCapability.BACKUPS.id) ||
                serverFeatureResolver.resolve(server).backups.create != null

    /**
     * Sends one server's full schedule set to whoever runs it.
     *
     * The runner is the resolver's choice (node > plugin > Pano), so a linked server with an
     * agent-lite plugin gets its schedules pushed into the game the same way a managed one gets
     * them pushed to its node. A server Pano runs the clock for is a no-op: there is nobody to
     * tell.
     */
    suspend fun syncServer(server: Server, sqlClient: SqlClient) {
        val runner = serverFeatureResolver.resolve(server).schedules.runner

        if (runner == null || runner == ServerFeatureSource.PANO) {
            return
        }

        val uuid = server.uuid ?: return

        val entries = listDetailed(server.id, sqlClient).map { detailed ->
            SyncScheduleEntry(
                uuid = detailed.schedule.uuid,
                name = detailed.schedule.name,
                cron = detailed.schedule.cron,
                timezone = detailed.schedule.timezone,
                enabled = detailed.schedule.enabled,
                warnMinutes = detailed.schedule.warnMinutes,
                tasks = detailed.tasks.map { task ->
                    // Stored as the operator sent it; expanded here so the runner always gets the
                    // full exclude list, like a backup taken from the panel.
                    SyncScheduleTaskEntry(
                        task.kind.name,
                        ScheduleDefinitions.relayPayload(task.kind, task.payloadObject()).map
                    )
                }
            )
        }

        val message = SyncSchedulesMessage(uuid, entries)

        if (runner == ServerFeatureSource.NODE) {
            val nodeId = server.nodeId ?: return

            nodeManager.sendMessage(nodeId, message)
        } else {
            // The plugin socket already identifies the server, so the uuid is stripped on the way
            // out; PluginSideChannel is what does that, and this is the one push that does not go
            // through it because there is no file target to resolve here.
            serverManager.sendMessage(
                server.id,
                RelayServerMessage(
                    message.getResponseName(),
                    JsonObject.mapFrom(message).apply { remove("serverUuid") }
                )
            )
        }
    }

    private suspend fun writeTasks(scheduleId: Long, definition: ScheduleDefinition, sqlClient: SqlClient) {
        definition.tasks.forEachIndexed { index, task ->
            databaseManager.serverScheduleTaskDao.add(
                ServerScheduleTask(
                    scheduleId = scheduleId,
                    position = index,
                    kind = task.kind,
                    payload = task.payload.encode()
                ),
                sqlClient
            )
        }
    }

    private suspend fun afterChange(server: Server, sqlClient: SqlClient) {
        syncServer(server, sqlClient)

        panelRealtimeHub.pushServerSchedulesChanged(server.id)
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 1000
    }
}
