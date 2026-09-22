package com.panomc.node.schedule

import com.panomc.node.console.ConsoleLevel
import com.panomc.node.files.BackupService
import com.panomc.node.net.BackupCreateMessage
import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import com.panomc.node.net.SyncScheduleEntry
import com.panomc.node.net.SyncSchedulesMessage
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The clock behind a managed server's schedules.
 *
 * It lives on the node rather than in Pano for one reason: the node is still here when Pano is
 * not. A platform being restarted at 03:59 must not cost a server its 04:00 backup, and a node
 * that has been handed its schedules can keep them without anyone watching.
 *
 * A tick is one minute, because cron has no finer resolution and because the timer never fires on
 * the same millisecond twice — so "is this due?" is asked about the minute, and a per-schedule
 * guard keyed by that minute is what stops a slow tick from running the same job twice.
 *
 * Steps run in order and stop at the first failure, which is the whole reason they are ordered:
 * "back up, then restart" must not restart when the backup failed.
 */
class ScheduleRunner(
    private val registry: ServerRegistry,
    private val backupService: BackupService,
    private val connection: PlatformConnection,
    private val logger: NodeLogger
) {
    /** Schedules per server uuid, exactly as Pano last sent them. */
    private val schedules = ConcurrentHashMap<String, List<SyncScheduleEntry>>()

    private val lastFiredMinute = ConcurrentHashMap<String, Long>()

    private val lastWarnedMinute = ConcurrentHashMap<String, Long>()

    /** Replaces everything this node knew about one server's schedules. */
    fun sync(message: SyncSchedulesMessage) {
        val uuid = message.serverUuid

        if (uuid.isNullOrBlank()) {
            logger.warn("Ignoring a SYNC_SCHEDULES with no server uuid.")

            return
        }

        val entries = (message.schedules ?: emptyList()).filter { entry ->
            val valid = !entry.uuid.isNullOrBlank() && CronSchedules.isValid(entry.cron)

            if (!valid) {
                logger.warn("Dropping an unusable schedule for server $uuid.")
            }

            valid
        }

        if (entries.isEmpty()) {
            schedules.remove(uuid)
        } else {
            schedules[uuid] = entries
        }

        logger.info("Now holding ${entries.size} schedule(s) for server $uuid.")
    }

    /** Forgets a server's schedules, for when it is deleted. */
    fun forget(serverUuid: String) {
        schedules.remove(serverUuid)
    }

    /** One pass over everything this node holds. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val minute = now / MINUTE_MILLIS

        schedules.forEach { (serverUuid, entries) ->
            val server = registry.get(serverUuid) ?: return@forEach

            entries.forEach { entry ->
                if (entry.enabled == false) {
                    return@forEach
                }

                try {
                    apply(server, entry, now, minute)
                } catch (exception: Exception) {
                    logger.warn("Schedule \"${entry.name}\" of $serverUuid failed: ${exception.message}")
                }
            }
        }

        prune(minute)
    }

    private fun apply(server: ServerProcess, entry: SyncScheduleEntry, now: Long, minute: Long) {
        val uuid = entry.uuid ?: return

        if (CronSchedules.firesAt(entry.cron, entry.timezone, now)) {
            if (lastFiredMinute.put(uuid, minute) == minute) {
                return
            }

            run(server, entry)

            return
        }

        warnIfDue(server, entry, now, minute)
    }

    /**
     * Sends the countdown lines that precede a power step.
     *
     * Asked forwards — "does this fire five minutes from now?" — rather than derived from a stored
     * next-run time, so it needs no state that could be stale and no arithmetic that a daylight
     * saving change would break.
     */
    private fun warnIfDue(server: ServerProcess, entry: SyncScheduleEntry, now: Long, minute: Long) {
        val offsets = ScheduleWarnings.offsetsFor(entry.warnMinutes ?: 0)

        if (offsets.isEmpty()) {
            return
        }

        val power = entry.tasks.orEmpty().firstOrNull { it.kind.equals(KIND_POWER, ignoreCase = true) } ?: return

        val due = offsets.firstOrNull { offset ->
            CronSchedules.firesAt(entry.cron, entry.timezone, now + offset * MINUTE_MILLIS)
        } ?: return

        val key = "${entry.uuid}:$due"

        if (lastWarnedMinute.put(key, minute) == minute) {
            return
        }

        val restarting = power.payload?.get("action")?.toString().equals(ACTION_RESTART, ignoreCase = true)

        server.sendCommand(ScheduleWarnings.message(due, restarting), issuerFor(entry))
    }

    /** Runs one schedule's steps and reports the outcome to Pano. */
    private fun run(server: ServerProcess, entry: SyncScheduleEntry) {
        val startedAt = System.currentTimeMillis()

        var error: String? = null

        for (task in entry.tasks.orEmpty()) {
            error = try {
                runTask(server, entry, task.kind, task.payload)
            } catch (exception: Exception) {
                exception.message ?: exception.javaClass.simpleName
            }

            if (error != null) {
                break
            }
        }

        report(server.uuid, entry, startedAt, error)
    }

    /** Returns null on success, or the reason it failed. */
    private fun runTask(
        server: ServerProcess,
        entry: SyncScheduleEntry,
        kind: String?,
        payload: Map<String, Any?>?
    ): String? {
        val issuer = issuerFor(entry)

        return when (kind?.uppercase()) {
            KIND_COMMAND -> {
                val command = payload?.get("command")?.toString().orEmpty()

                if (command.isBlank()) {
                    "The command step had no command."
                } else if (!server.sendCommand(command, issuer)) {
                    "The server was not running."
                } else {
                    null
                }
            }

            KIND_POWER -> {
                when (payload?.get("action")?.toString()?.uppercase()) {
                    ACTION_STOP -> {
                        server.stop(issuer)

                        null
                    }

                    ACTION_RESTART -> {
                        server.restart(issuer)

                        null
                    }

                    else -> "\"${payload?.get("action")}\" is not something a schedule may do."
                }
            }

            KIND_BACKUP -> {
                server.emit(ConsoleLevel.INFO, "Pano is taking a scheduled backup.")

                // Ids are invented here because this run is the node's: Pano first hears about the
                // archive when BACKUP_CREATED arrives and writes its row from that.
                backupService.create(
                    BackupCreateMessage(
                        serverUuid = server.uuid,
                        taskId = UUID.randomUUID().toString(),
                        backupId = UUID.randomUUID().toString(),
                        name = payload?.get("name")?.toString()?.takeIf { it.isNotBlank() }
                            ?: "${entry.name.orEmpty().ifEmpty { "schedule" }}-${System.currentTimeMillis()}",
                        // Same meaning as on a BACKUP_CREATE from Pano; absent keys are the old
                        // FULL-of-everything backup, so a schedule saved before v2 runs unchanged.
                        exclude = stringList(payload?.get("exclude")),
                        mode = payload?.get("mode")?.toString()?.takeIf { it.isNotBlank() },
                        scope = payload?.get("scope")?.toString()?.takeIf { it.isNotBlank() },
                        include = stringList(payload?.get("include"))
                    )
                )

                null
            }

            else -> "\"$kind\" is not a step this node understands."
        }
    }

    private fun report(serverUuid: String, entry: SyncScheduleEntry, startedAt: Long, error: String?) {
        connection.send(
            NodeProtocol.Outbound.SCHEDULE_RUN,
            JsonObject()
                .put("serverUuid", serverUuid)
                .put("scheduleUuid", entry.uuid)
                .put("startedAt", startedAt)
                .put("finishedAt", System.currentTimeMillis())
                .put("ok", error == null)
                .put("error", error)
        )
    }

    /**
     * A payload value as a list of strings, or null when it is not a list.
     *
     * The payload is decoded without a type, so a JSON array arrives as whatever list Gson made of
     * it; anything that is not a string inside it is dropped rather than turned into its toString.
     */
    private fun stringList(value: Any?): List<String>? =
        (value as? List<*>)?.filterIsInstance<String>()

    private fun issuerFor(entry: SyncScheduleEntry) = "schedule:${entry.name.orEmpty().take(MAX_ISSUER_LENGTH)}"

    private fun prune(minute: Long) {
        lastFiredMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
        lastWarnedMinute.entries.removeIf { minute - it.value > GUARD_RETENTION_MINUTES }
    }

    companion object {
        const val TICK_INTERVAL_MILLIS = 60_000L

        private const val MINUTE_MILLIS = 60_000L
        private const val GUARD_RETENTION_MINUTES = 5
        private const val MAX_ISSUER_LENGTH = 32

        private const val KIND_POWER = "POWER"
        private const val KIND_COMMAND = "COMMAND"
        private const val KIND_BACKUP = "BACKUP"

        private const val ACTION_STOP = "STOP"
        private const val ACTION_RESTART = "RESTART"
    }
}
