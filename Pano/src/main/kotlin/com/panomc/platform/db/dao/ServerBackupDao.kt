package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerBackup
import com.panomc.platform.server.backup.BackupMode
import com.panomc.platform.server.backup.BackupScope
import com.panomc.platform.server.backup.ServerBackupStatus
import io.vertx.sqlclient.SqlClient

abstract class ServerBackupDao : Dao<ServerBackup>(ServerBackup::class.java) {
    abstract suspend fun add(
        serverBackup: ServerBackup,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): ServerBackup?

    abstract suspend fun getByUuid(
        uuid: String,
        sqlClient: SqlClient
    ): ServerBackup?

    /** Newest first, which is the order both the panel and retention want. */
    abstract suspend fun getAllByServerId(
        serverId: Long,
        sqlClient: SqlClient
    ): List<ServerBackup>

    /**
     * Fills in what the source reported once the backup exists. [mode] and [scope] are
     * overwritten too, because what the source says it made is the truth and what Pano asked for
     * is only a request an older source may have ignored.
     */
    abstract suspend fun updateResultByUuid(
        uuid: String,
        sizeBytes: Long,
        sha256: String?,
        status: ServerBackupStatus,
        mode: BackupMode,
        scope: BackupScope,
        fileCount: Long?,
        storedBytes: Long?,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePinnedById(
        id: Long,
        pinned: Boolean,
        sqlClient: SqlClient
    )

    /** Marks every unfinished backup of a server as failed, after its task reported FAILED. */
    abstract suspend fun failCreatingByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )

    /** Backups started on or after [since], for the telemetry usage counters. */
    abstract suspend fun countCreatedSince(
        since: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByNodeId(
        nodeId: Long,
        sqlClient: SqlClient
    )
}
