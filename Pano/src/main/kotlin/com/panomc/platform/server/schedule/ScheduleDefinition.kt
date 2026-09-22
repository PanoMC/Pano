package com.panomc.platform.server.schedule

import com.panomc.platform.error.InvalidData
import com.panomc.platform.server.ServerPowerAction
import com.panomc.platform.server.backup.BackupOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * A schedule as the panel sends it, after it has been checked.
 *
 * Validation lives here, apart from both the endpoints and the database, because create and update
 * take exactly the same body and a rule enforced in one of them is a rule missing from the other.
 * Everything a node will later act on unattended is decided here: a command that is empty, a cron
 * that cannot be parsed, a backup asked of a server with no files — all of them fail now, in front
 * of the person who typed them, rather than at four in the morning.
 */
data class ScheduleDefinition(
    val name: String,
    val cron: String,
    val timezone: String,
    val enabled: Boolean,
    val warnMinutes: Int,
    val tasks: List<ScheduleTaskDefinition>
)

data class ScheduleTaskDefinition(
    val kind: ScheduleTaskKind,
    val payload: JsonObject
)

object ScheduleDefinitions {
    /**
     * A stored BACKUP step's payload as the node or plugin must receive it: `exclude` expanded to
     * the full list (defaults first unless `excludeDefaults` is false) and `include` dropped unless
     * the scope is CUSTOM. Everything else is passed through untouched.
     *
     * A step stored before backups v2 has none of the new fields and comes out as a full zip of
     * everything with the default excludes, which is what it always did.
     */
    fun relayPayload(kind: ScheduleTaskKind, payload: JsonObject): JsonObject {
        if (kind != ScheduleTaskKind.BACKUP) {
            return payload
        }

        // A stored payload is one this class wrote, but a row edited by hand must not stop every
        // other schedule of the server from syncing: anything unreadable falls back to defaults.
        val options = try {
            BackupOptions.parse(payload)
        } catch (_: Exception) {
            BackupOptions()
        }

        return payload.copy()
            .put("mode", options.mode.name)
            .put("scope", options.scope.name)
            .put("include", JsonArray(options.include))
            .put("exclude", JsonArray(options.effectiveExclude()))
            .apply { remove("excludeDefaults") }
    }

    const val MAX_NAME_LENGTH = 255
    const val MAX_COMMAND_LENGTH = 1024
    const val MAX_TASKS = 20

    /** Ceiling on a per-schedule retention override, mirroring the server-wide one. */
    const val MAX_KEEP = 100

    /**
     * Reads one schedule out of a request body, or throws [InvalidData].
     *
     * [backupsAllowed] is false for a server nothing can back up — no node, and no plugin with the
     * `backups` capability — so a BACKUP step would be a schedule that can only ever fail, and it
     * is refused at save time.
     */
    fun parse(body: JsonObject, backupsAllowed: Boolean, defaultTimezone: String): ScheduleDefinition {
        val name = body.getString("name")?.trim().orEmpty()

        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw InvalidData(extras = mapOf("field" to "name"))
        }

        val cron = body.getString("cron")?.trim().orEmpty()

        if (!CronSchedules.isValid(cron)) {
            throw InvalidData(extras = mapOf("field" to "cron"))
        }

        val timezone = body.getString("timezone")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTimezone

        if (!CronSchedules.isValidZone(timezone)) {
            throw InvalidData(extras = mapOf("field" to "timezone"))
        }

        val warnMinutes = (body.getInteger("warnMinutes") ?: 0).coerceIn(0, ScheduleWarnings.MAX_WARN_MINUTES)

        val rawTasks = body.getJsonArray("tasks") ?: JsonArray()

        if (rawTasks.isEmpty || rawTasks.size() > MAX_TASKS) {
            throw InvalidData(extras = mapOf("field" to "tasks"))
        }

        val tasks = rawTasks.map { entry ->
            val task = entry as? JsonObject ?: throw InvalidData(extras = mapOf("field" to "tasks"))

            parseTask(task, backupsAllowed)
        }

        return ScheduleDefinition(
            name = name,
            cron = cron,
            timezone = timezone,
            enabled = body.getBoolean("enabled", true) ?: true,
            warnMinutes = warnMinutes,
            tasks = tasks
        )
    }

    private fun parseTask(task: JsonObject, backupsAllowed: Boolean): ScheduleTaskDefinition {
        val kind = ScheduleTaskKind.fromId(task.getString("kind"))
            ?: throw InvalidData(extras = mapOf("field" to "kind"))

        val payload = task.getJsonObject("payload") ?: JsonObject()

        return when (kind) {
            ScheduleTaskKind.POWER -> {
                val action = ServerPowerAction.fromId(payload.getString("action"))

                // Only the two a schedule can meaningfully order: START would be a schedule that
                // starts a server nobody asked to stop, and KILL is a last resort a person takes,
                // not something a timer should do to a world mid-save.
                if (action != ServerPowerAction.STOP && action != ServerPowerAction.RESTART) {
                    throw InvalidData(extras = mapOf("field" to "action"))
                }

                ScheduleTaskDefinition(kind, JsonObject().put("action", action.name))
            }

            ScheduleTaskKind.COMMAND -> {
                val command = payload.getString("command")?.trim().orEmpty()

                if (command.isEmpty() || command.length > MAX_COMMAND_LENGTH) {
                    throw InvalidData(extras = mapOf("field" to "command"))
                }

                ScheduleTaskDefinition(kind, JsonObject().put("command", command))
            }

            ScheduleTaskKind.BACKUP -> {
                if (!backupsAllowed) {
                    throw InvalidData(extras = mapOf("field" to "kind", "reason" to "BACKUP_NOT_SUPPORTED"))
                }

                val backupName = payload.getString("name")?.trim()?.takeIf { it.isNotEmpty() }

                // `keep` overrides the server's retention for backups this schedule takes, so a
                // nightly job can keep seven copies while a weekly one keeps four. Absent means
                // "whatever the server is set to".
                val keep = payload.getInteger("keep")?.coerceIn(1, MAX_KEEP)

                // Same five fields, same rules as a backup taken by hand. Stored as the operator
                // sent them — `exclude` is the extras and `excludeDefaults` says whether the
                // defaults go in front — and expanded only on the way to whoever runs the step.
                val options = BackupOptions.parse(payload)

                ScheduleTaskDefinition(
                    kind,
                    options.toPayload()
                        .put("name", backupName?.take(MAX_NAME_LENGTH))
                        .put("keep", keep)
                )
            }
        }
    }
}
