package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerPluginInstall
import io.vertx.sqlclient.SqlClient

abstract class ServerPluginInstallDao : Dao<ServerPluginInstall>(ServerPluginInstall::class.java) {
    /**
     * Records where one jar came from, replacing whatever was known about that filename.
     *
     * An upsert rather than an add because the filename is the identity here: installing the same
     * build twice, or identifying a jar Pano installed itself, must end with one row and the
     * newest facts in it.
     */
    abstract suspend fun upsert(serverPluginInstall: ServerPluginInstall, sqlClient: SqlClient)

    abstract suspend fun getByServerId(serverId: Long, sqlClient: SqlClient): List<ServerPluginInstall>

    abstract suspend fun getByServerIdAndFilename(
        serverId: Long,
        filename: String,
        sqlClient: SqlClient
    ): ServerPluginInstall?

    /** Every server that has something worth checking for updates, for the daily sweep. */
    abstract suspend fun getServerIdsWithRows(sqlClient: SqlClient): List<Long>

    abstract suspend fun deleteByServerIdAndFilename(serverId: Long, filename: String, sqlClient: SqlClient)

    abstract suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient)

    /** Drops the row an install was going to justify, for an install that failed. */
    abstract suspend fun deleteByTaskId(taskId: String, sqlClient: SqlClient)

    /** Turns a promised row into a fact: the jar is on disk, so the task no longer owns the row. */
    abstract suspend fun clearTaskId(taskId: String, sqlClient: SqlClient)
}
