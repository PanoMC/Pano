package com.panomc.platform.server.backup

/**
 * What one managed server sends to Pano Backup (the connected account's storage) and how long it is kept there.
 *
 * Off by default: the backups of a server go to the account only when its owner says so on the server's Backups
 * page. [mode] picks which of the server's backups are uploaded — its finished full zips or its snapshots — and
 * [retention] is applied at Pano Backup after every upload, counted per server and apart from the local keep-last.
 *
 * Every property has a default, so a settings row written before this existed reads as "off" (the same no-arg
 * constructor rule [com.panomc.platform.db.model.Server.Companion.ServerSettings] relies on).
 */
data class ServerPanoBackupSettings(
    var enabled: Boolean = false,
    var mode: BackupMode = BackupMode.FULL,
    var retention: PanoBackupRetention = PanoBackupRetention.COUNT,
    /** Backups kept by [PanoBackupRetention.COUNT]. */
    var keepLast: Int = DEFAULT_KEEP_LAST,
    /** Days kept by [PanoBackupRetention.DAYS]. */
    var keepDays: Int = DEFAULT_KEEP_DAYS
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "mode" to mode.name,
        "retention" to retention.name,
        "keepLast" to keepLast,
        "keepDays" to keepDays
    )

    companion object {
        const val DEFAULT_KEEP_LAST = 7
        const val DEFAULT_KEEP_DAYS = 30
        const val MAX_KEEP_LAST = 500
        const val MAX_KEEP_DAYS = 3650
    }
}

/** How a server's backups at Pano Backup are kept: all of them (only the quota limits), the last N, or the last X days. */
enum class PanoBackupRetention {
    NONE,
    COUNT,
    DAYS;

    companion object {
        fun fromId(id: String?): PanoBackupRetention? = entries.firstOrNull { it.name == id?.trim()?.uppercase() }
    }
}

/**
 * Which of one server's backups at Pano Backup the retention says are surplus.
 *
 * Pure, so the rule can be tested apart from the account it deletes from:
 * - only finished rows count and only finished rows are chosen; an upload still running is not a copy yet;
 * - [keep] (the backup just uploaded) is never chosen, whatever its date says — a clock that is off must not make
 *   the upload that just succeeded the one that is deleted;
 * - COUNT keeps the newest N; DAYS keeps what was made within the last X days, and always the newest one, so a server
 *   whose backups stopped a month ago is not left with nothing at all;
 * - a limit below one reads as the default, never as "delete everything".
 */
object PanoBackupRetentionRule {
    data class Row(val id: String, val createdAt: Long)

    fun selectForDeletion(rows: List<Row>, settings: ServerPanoBackupSettings, now: Long, keep: String?): List<Row> {
        val newestFirst = rows.sortedWith(compareByDescending<Row> { it.createdAt }.thenByDescending { it.id })

        return when (settings.retention) {
            PanoBackupRetention.NONE -> emptyList()

            PanoBackupRetention.COUNT -> {
                val limit = settings.keepLast.takeIf { it > 0 } ?: ServerPanoBackupSettings.DEFAULT_KEEP_LAST

                newestFirst.drop(limit)
            }

            PanoBackupRetention.DAYS -> {
                val days = settings.keepDays.takeIf { it > 0 } ?: ServerPanoBackupSettings.DEFAULT_KEEP_DAYS
                val cutoff = now - days * DAY_MS

                newestFirst.drop(1).filter { it.createdAt < cutoff }
            }
        }.filter { it.id != keep }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
