package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.PermissionNode
import io.vertx.sqlclient.SqlClient

abstract class PermissionNodeDao : Dao<PermissionNode>(PermissionNode::class.java) {
    abstract suspend fun add(
        permissionNode: PermissionNode,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getPermissionNodes(
        sqlClient: SqlClient
    ): List<PermissionNode>

    abstract suspend fun deleteByIds(
        ids: List<Long>,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    )
}

