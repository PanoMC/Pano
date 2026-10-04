package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.NodePendingDeletionDaoImpl
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds `node_pending_deletion` (SM-64, §2.4.29 A): the managed servers Pano force-deleted while
 * their node could not delete the files, so the node's next hello can be told to.
 */
@Migration
class DatabaseMigration51to52 : DatabaseMigration(
    51,
    52,
    "Add the node_pending_deletion table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            sqlClient
                .preparedQuery(NodePendingDeletionDaoImpl.createTableQuery("${getTablePrefix()}node_pending_deletion"))
                .execute()
                .coAwait()
        }
    )
}
