package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.NodeRequestMessage

/**
 * Tells a node to take a backup.
 *
 * Fire and forget, like an install: a backup runs for minutes, so it reports through
 * `TASK_PROGRESS` and finishes with a `BACKUP_CREATED` event rather than by answering here.
 * [backupId] is chosen by Pano so the row and the archive share a name from the very start.
 */
class BackupCreateMessage(
    val serverUuid: String,
    val taskId: String,
    val backupId: String,
    val name: String,
    /**
     * The full exclude list: Pano sends the defaults (`logs/`, `cache/`, `*.jar.tmp`) plus the
     * operator's extras. Empty still means the other side applies its own defaults.
     */
    val exclude: List<String> = emptyList(),
    /**
     * `FULL` or `SNAPSHOT` (backups v2). Always sent; a node or plugin that predates modes ignores
     * it and makes a full zip, and its `BACKUP_CREATED` says so by not naming a mode.
     */
    val mode: String = "FULL",
    /** `ALL`, `WORLDS` or `CUSTOM`. */
    val scope: String = "ALL",
    /** Roots of a `CUSTOM` backup; null for every other scope. */
    val include: List<String>? = null
) : NodeMessage

/** Asks a node what backups it actually holds, which is the truth Pano's rows only mirror. */
class BackupListMessage(
    val serverUuid: String
) : NodeRequestMessage()

/**
 * Tells the other side to put a backup back. Refused by a node unless the server is stopped.
 *
 * A request rather than a push since SM-47, because the two sides answer it differently and the
 * panel has to say which happened: a node overwrites the files there and then, while the plugin
 * can only arm the restore for the next start and replies `{ ok, mode: "next-start" }` to say so
 * (§2.4.17 C). A node that answers nothing is unchanged — the restore is tracked by its task.
 */
class BackupRestoreMessage(
    val serverUuid: String,
    val backupId: String,
    val taskId: String,
    /** Who asked for it, for the plugin's own marker file. */
    val requestedBy: String? = null
) : NodeRequestMessage()

/**
 * Tells a node to remove one backup.
 *
 * A request rather than a push: retention deletes rows on the strength of this, and a row deleted
 * while the archive is still on disk is a file nobody can ever reach again.
 */
class BackupDeleteMessage(
    val serverUuid: String,
    val backupId: String
) : NodeRequestMessage()
