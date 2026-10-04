package com.panomc.platform.server.schedule

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.ServerSchedule
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The clock behind every linked server's schedules.
 *
 * Which servers those are is [com.panomc.platform.server.feature.ServerFeature.SCHEDULES_RUNNER]'s
 * decision. A node runs its own servers' schedules and an agent-lite plugin runs its own server's,
 * because both keep firing while Pano is restarting at 03:59 and neither costs anybody their 04:00
 * backup. Pano keeps the clock for everything that has neither, and a run missed while Pano was
 * down is simply missed — the honest behaviour rather than a catch-up burst of restarts on boot.
 *
 * Ticking every sixty seconds makes the minute the unit of scheduling, and cron has no finer
 * resolution anyway. The tick never lands on the same millisecond twice, so "did this fire?" is
 * asked about the minute rather than the instant, and a guard keyed by schedule and minute is what
 * stops a slow tick from firing the same run twice.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ScheduleRunner(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val scheduleService: ServerScheduleService,
    private val scheduleExecutor: ScheduleExecutor,
    private val serverFeatureResolver: ServerFeatureResolver
) {
    @Autowired
    private lateinit var logger: Logger

    /** Last minute each schedule was acted on, so one tick's slowness cannot double-fire it. */
    private val lastFiredMinute = ConcurrentHashMap<Long, Long>()

    private val lastWarnedMinute = ConcurrentHashMap<String, Long>()

    fun start() {
        logger.info("Started server schedule runner.")

        vertx.setPeriodic(TICK_INTERVAL_MS) {
            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    tick(System.currentTimeMillis())
                } catch (e: Exception) {
                    logger.error("Failed to run server schedules", e)
                }
            }
        }
    }

    /** One pass over every enabled schedule. Public so a run can be forced in a test or a command. */
    suspend fun tick(now: Long) {
        if (!setupManager.isSetupDone()) {
            return
        }

        val sqlClient = databaseManager.getSqlClient()

        val schedules = databaseManager.serverScheduleDao.getAllEnabled(sqlClient)

        if (schedules.isEmpty()) {
            return
        }

        val minute = now / TICK_INTERVAL_MS

        // One lookup per server rather than per schedule: a server commonly has several.
        val servers = mutableMapOf<Long, Server?>()

        schedules.forEach { schedule ->
            val server = servers.getOrPut(schedule.serverId) {
                databaseManager.serverDao.getById(schedule.serverId, sqlClient)
            } ?: return@forEach

            // A server whose schedules are run by its node or by its own Pano plugin is not
            // Pano's to run: doing it here as well would mean every one of them happening twice.
            // Pano keeps the clock only for a server that has nothing better (§2.4.17 A).
            if (serverFeatureResolver.resolve(server).schedules.runner != ServerFeatureSource.PANO) {
                return@forEach
            }

            try {
                apply(server, schedule, now, minute, sqlClient)
            } catch (e: Exception) {
                logger.warn("Schedule \"${schedule.name}\" of server ${server.id} failed: ${e.message}")
            }
        }

        prune(minute)
    }

    private suspend fun apply(
        server: Server,
        schedule: ServerSchedule,
        now: Long,
        minute: Long,
        sqlClient: SqlClient
    ) {
        if (CronSchedules.firesAt(schedule.cron, schedule.timezone, now)) {
            if (lastFiredMinute.put(schedule.id, minute) == minute) {
                return
            }

            runNow(server, schedule, issuedBy = null, sqlClient = sqlClient)

            return
        }

        warnIfDue(server, schedule, now, minute, sqlClient)
    }

    /**
     * Sends the countdown lines that precede a power step.
     *
     * Computed forwards from the current minute rather than backwards from a stored "next run":
     * asking "does this schedule fire five minutes from now" is a question cron can answer exactly,
     * and it needs no state that could go stale while Pano was down.
     */
    private suspend fun warnIfDue(
        server: Server,
        schedule: ServerSchedule,
        now: Long,
        minute: Long,
        sqlClient: SqlClient
    ) {
        val offsets = ScheduleWarnings.offsetsFor(schedule.warnMinutes)

        if (offsets.isEmpty() || !scheduleExecutor.isReachable(server)) {
            return
        }

        val due = offsets.firstOrNull { offset ->
            CronSchedules.firesAt(schedule.cron, schedule.timezone, now + offset * TICK_INTERVAL_MS)
        } ?: return

        val tasks = databaseManager.serverScheduleTaskDao.getAllByScheduleId(schedule.id, sqlClient)

        val power = tasks.firstOrNull { it.kind == ScheduleTaskKind.POWER } ?: return

        val key = "${schedule.id}:$due"

        if (lastWarnedMinute.put(key, minute) == minute) {
            return
        }

        scheduleExecutor.warn(server, due, power.payloadObject().getString("action") == "RESTART")
    }

    /** Runs one schedule immediately and records the outcome. Used by the panel's Run button too. */
    suspend fun runNow(server: Server, schedule: ServerSchedule, issuedBy: Long?, sqlClient: SqlClient) {
        val startedAt = System.currentTimeMillis()

        val tasks = databaseManager.serverScheduleTaskDao.getAllByScheduleId(schedule.id, sqlClient)

        val outcome = scheduleExecutor.run(server, schedule.name, tasks, issuedBy, sqlClient)

        scheduleService.recordRun(server, schedule, outcome.status, outcome.error, startedAt, sqlClient)
    }

    /** Drops guard entries for minutes that have passed, so neither map grows forever. */
    private fun prune(minute: Long) {
        lastFiredMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
        lastWarnedMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
    }

    companion object {
        const val TICK_INTERVAL_MS = 60L * 1000L

        private const val GUARD_RETENTION_MINUTES = 5
    }
}
