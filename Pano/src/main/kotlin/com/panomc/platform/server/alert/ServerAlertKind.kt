package com.panomc.platform.server.alert

/**
 * The things worth waking somebody up about, and how often each may say so.
 *
 * The cooldowns are the whole design of this feature. Every one of these conditions is derived
 * from a signal that repeats — metrics arrive every ten seconds, a heartbeat sweep runs every
 * fifteen, a schedule may fire every minute — so an alert with no cooldown is not an alert, it is
 * a stream. The interval per kind is how long the condition has to keep being true before it is
 * worth saying again, chosen from how fast each one can realistically be acted on: a disk filling
 * up is a six-hour problem, a server crashing is a five-minute one.
 *
 * [id] is where this kind's e-mail text lives: `mail.server-alert.kinds.<id>` in every locale file
 * holds its headline and the sentences its message is built from, so an admin who reads Pano in
 * Turkish is told about a crash in Turkish. It is written out rather than derived from the enum
 * name because renaming a constant is a refactor, while the key a translator wrote against is a
 * promise. What is stored in `server_alert` and written to the log stays English — the row is the
 * record, and a record is not per reader.
 */
enum class ServerAlertKind(val cooldownMs: Long, val id: String) {
    /** A managed server's process exited without being asked to. */
    SERVER_CRASHED(5 * MINUTE, "server-crashed"),

    /** A node stopped answering, so everything it runs is out of Pano's reach. */
    NODE_OFFLINE(15 * MINUTE, "node-offline"),

    /** A backup task ended in FAILED, which means there is no copy of that world tonight. */
    BACKUP_FAILED(30 * MINUTE, "backup-failed"),

    /** A node's data disk is nearly full, which is how installs and backups start failing. */
    DISK_LOW(6 * HOUR, "disk-low"),

    /** A server has been running below playable tick rate for long enough to not be a blip. */
    TPS_LOW(30 * MINUTE, "tps-low"),

    /** A schedule ran and something in it did not work. */
    SCHEDULE_FAILED(30 * MINUTE, "schedule-failed"),

    /**
     * A server is running plugins their authors have since published a newer build of.
     *
     * The one kind here that is not about something breaking, and the cooldown says so: an
     * out-of-date plugin is a thing to deal with this week, so it is raised by a daily sweep and
     * may say so once a day. Anything more often would be a newsletter.
     */
    PLUGIN_UPDATES(24 * HOUR, "plugin-updates");

    /**
     * Whether this kind is about one server, and so can be switched on or off for that server alone
     * (its `settings.alerts`, which overrides the platform-wide switch). The node kinds are about a
     * machine that may run many servers, and stay platform-wide.
     */
    val serverScoped: Boolean get() = this != NODE_OFFLINE && this != DISK_LOW

    companion object {
        /** The kind a stored row or a panel request names, which is the enum's own name and not [id]. */
        fun fromId(name: String?): ServerAlertKind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

private const val MINUTE = 60L * 1000L
private const val HOUR = 60L * MINUTE
