package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.NodePendingDeletionDao
import com.panomc.platform.db.model.NodePendingDeletion
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class NodePendingDeletionDaoImpl : NodePendingDeletionDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(getTablePrefix() + tableName))
            .execute()
            .coAwait()
    }

    override suspend fun add(nodeId: Long, serverUuid: String, sqlClient: SqlClient) {
        // INSERT IGNORE on the unique key: forcing the same delete twice is not two deletions.
        val query = "INSERT IGNORE INTO `${getTablePrefix() + tableName}` " +
                "(`nodeId`, `serverUuid`, `createdAt`) VALUES (?, ?, ?)"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId, serverUuid, System.currentTimeMillis()))
            .coAwait()
    }

    override suspend fun getAllByNodeId(nodeId: Long, sqlClient: SqlClient): List<NodePendingDeletion> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `nodeId` = ? ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteByNodeIdAndServerUuid(nodeId: Long, serverUuid: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ? AND `serverUuid` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(nodeId, serverUuid)).coAwait()
    }

    override suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(nodeId)).coAwait()
    }

    companion object {
        /** Shared with `DatabaseMigration51to52`, so a fresh install and an upgrade get one table. */
        fun createTableQuery(table: String) = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `nodeId` bigint NOT NULL,
              `serverUuid` varchar(36) NOT NULL,
              `createdAt` bigint NOT NULL,
              PRIMARY KEY (`id`),
              UNIQUE KEY `idx_node_pending_deletion_server` (`nodeId`, `serverUuid`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Force-deleted managed servers a node still has to remove.';
        """.trimIndent()
    }
}
