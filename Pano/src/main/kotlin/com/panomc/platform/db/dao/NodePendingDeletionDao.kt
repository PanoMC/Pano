package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.NodePendingDeletion
import io.vertx.sqlclient.SqlClient

abstract class NodePendingDeletionDao : Dao<NodePendingDeletion>(NodePendingDeletion::class.java) {
    /** Records [serverUuid] as waiting for [nodeId] to delete it; a second record is a no-op. */
    abstract suspend fun add(nodeId: Long, serverUuid: String, sqlClient: SqlClient)

    abstract suspend fun getAllByNodeId(nodeId: Long, sqlClient: SqlClient): List<NodePendingDeletion>

    abstract suspend fun deleteByNodeIdAndServerUuid(nodeId: Long, serverUuid: String, sqlClient: SqlClient)

    abstract suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient)
}
