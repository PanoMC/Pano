package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerAlertDao
import com.panomc.platform.db.model.ServerAlert
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerAlertDaoImpl : ServerAlertDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `kind` varchar(32) NOT NULL,
                              `serverId` bigint DEFAULT NULL,
                              `nodeId` bigint DEFAULT NULL,
                              `message` text NOT NULL,
                              `createdAt` bigint NOT NULL,
                              `resolvedAt` bigint DEFAULT NULL,
                              PRIMARY KEY (`id`),
                              KEY `idx_server_alert_created` (`createdAt`),
                              KEY `idx_server_alert_server` (`serverId`),
                              KEY `idx_server_alert_node` (`nodeId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Things that went wrong on a server or node.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(serverAlert: ServerAlert, sqlClient: SqlClient): Long {
        val query = "INSERT INTO `${getTablePrefix() + tableName}` " +
                "(`kind`, `serverId`, `nodeId`, `message`, `createdAt`, `resolvedAt`) VALUES (?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverAlert.kind.name,
                    serverAlert.serverId,
                    serverAlert.nodeId,
                    serverAlert.message,
                    serverAlert.createdAt,
                    serverAlert.resolvedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getLatest(limit: Int, sqlClient: SqlClient): List<ServerAlert> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "ORDER BY `createdAt` DESC, `id` DESC LIMIT ?"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(limit)).coAwait()

        return rows.toEntities()
    }

    override suspend fun getLatestByServerId(serverId: Long, limit: Int, sqlClient: SqlClient): List<ServerAlert> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? ORDER BY `createdAt` DESC, `id` DESC LIMIT ?"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(Tuple.of(serverId, limit)).coAwait()

        return rows.toEntities()
    }

    override suspend fun getLatestByKindAndSubject(
        kind: String,
        serverId: Long?,
        nodeId: Long?,
        sqlClient: SqlClient
    ): ServerAlert? {
        val table = "`${getTablePrefix() + tableName}`"

        // A server's rows are found by the server alone: they also carry the node the server was
        // on, which is not part of what the alert was about and may since have changed.
        val (where, parameters) = if (serverId != null) {
            "`kind` = ? AND `serverId` = ?" to Tuple.of(kind, serverId)
        } else {
            "`kind` = ? AND `serverId` IS NULL AND `nodeId` <=> ?" to Tuple.of(kind, nodeId)
        }

        val query = "SELECT ${fields.toTableQuery()} FROM $table WHERE $where ORDER BY `createdAt` DESC, `id` DESC LIMIT 1"

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(parameters).coAwait()

        return rows.toEntities().firstOrNull()
    }

    override suspend fun resolveOpen(
        kind: String,
        serverId: Long?,
        nodeId: Long?,
        resolvedAt: Long,
        sqlClient: SqlClient
    ) {
        // NULL-safe equality on both ids, so "this node, no server" matches exactly the rows it
        // should and a plain `=` does not silently match nothing.
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `resolvedAt` = ? " +
                "WHERE `kind` = ? AND `serverId` <=> ? AND `nodeId` <=> ? AND `resolvedAt` IS NULL"

        sqlClient.preparedQuery(query).execute(Tuple.of(resolvedAt, kind, serverId, nodeId)).coAwait()
    }

    override suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(serverId)).coAwait()
    }

    override suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(nodeId)).coAwait()
    }

    override suspend fun deleteOlderThan(before: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `createdAt` < ?"

        sqlClient.preparedQuery(query).execute(Tuple.of(before)).coAwait()
    }
}
