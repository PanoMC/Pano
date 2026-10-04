package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerAlert
import io.vertx.sqlclient.SqlClient

abstract class ServerAlertDao : Dao<ServerAlert>(ServerAlert::class.java) {
    abstract suspend fun add(serverAlert: ServerAlert, sqlClient: SqlClient): Long

    /** Newest first, which is the only order anyone wants these in. */
    abstract suspend fun getLatest(limit: Int, sqlClient: SqlClient): List<ServerAlert>

    abstract suspend fun getLatestByServerId(serverId: Long, limit: Int, sqlClient: SqlClient): List<ServerAlert>

    /**
     * The newest alert of one kind about one subject, or null when there has never been one.
     *
     * With a [serverId] the row is about that server, whichever node it ran on at the time; with
     * none it is a node-only row ([nodeId], no server) -- the two shapes `AlertManager` writes.
     * This is what the in-memory cooldown is seeded from after a restart (SM-69, §2.4.34).
     */
    abstract suspend fun getLatestByKindAndSubject(
        kind: String,
        serverId: Long?,
        nodeId: Long?,
        sqlClient: SqlClient
    ): ServerAlert?

    /** Closes every open alert of one kind about one subject, for when the condition goes away. */
    abstract suspend fun resolveOpen(
        kind: String,
        serverId: Long?,
        nodeId: Long?,
        resolvedAt: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient)

    abstract suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient)

    /** Drops anything older than [before], so the table does not grow forever. */
    abstract suspend fun deleteOlderThan(before: Long, sqlClient: SqlClient)
}
