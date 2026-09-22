package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.BackupScope
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.core.json.JsonObject

/**
 * One copy of a managed server's directory, as Pano knows it.
 *
 * The archive itself lives on the node, in `<data>/backups/<serverUuid>/<uuid>.zip`, and this row
 * is what makes it listable, downloadable and subject to retention without Pano having to ask
 * every node what it is holding. [uuid] is the id both sides use; the auto-increment [id] never
 * leaves Pano.
 */
data class ServerBackup(
    val id: Long = -1,
    /** Id shared with the node, and the archive's file name there. */
    val uuid: String,
    val serverId: Long,
    /**
     * Node that holds the archive, or null when the server's own Pano plugin took it (SM-47).
     *
     * Nullable rather than a sentinel because "no node" is a real state now: an agent-lite backup
     * lives in the server's own `backups/` directory and there is no daemon anywhere in the story.
     */
    val nodeId: Long? = null,
    var name: String,
    var sizeBytes: Long = 0,
    /** Hex SHA-256 of the archive, so a download can be verified against what was taken. */
    var sha256: String? = null,
    var status: ServerBackupStatus = ServerBackupStatus.CREATING,
    val createdBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * `FULL` zip or incremental `SNAPSHOT` (backups v2).
     *
     * Written from what the source reported in `BACKUP_CREATED` rather than from what was asked
     * for: a node or plugin too old to know modes makes a full zip whatever it is sent, and the
     * row has to say what is actually on disk. For a snapshot [sizeBytes] is the logical size of
     * every file in it and [sha256] is null.
     */
    var mode: BackupMode = BackupMode.FULL,
    var scope: BackupScope = BackupScope.ALL,
    /** Pinned backups are never chosen by retention, whatever the keep-last or the disk cap. */
    var pinned: Boolean = false,
    /** Files in the backup, null for a row taken before anybody counted. */
    var fileCount: Long? = null,
    /**
     * Bytes this backup actually wrote to disk: the archive for a full backup, only the new
     * compressed chunks for a snapshot. What the snapshot disk cap adds up.
     */
    var storedBytes: Long? = null,
    /** Roots of a `CUSTOM` backup, as a JSON array in a text column. */
    var include: List<String> = emptyList(),
    /** The full exclude list the source was sent, defaults included. */
    var exclude: List<String> = emptyList()
) : DBEntity() {
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this)

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean = other is ServerBackup && other.id == this.id
}
