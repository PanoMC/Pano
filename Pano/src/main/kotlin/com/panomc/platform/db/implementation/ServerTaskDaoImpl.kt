package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerTaskDao
import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ServerTaskStatus
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerTaskDaoImpl : ServerTaskDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(36) NOT NULL,
                              `serverId` bigint DEFAULT NULL,
                              `nodeId` bigint DEFAULT NULL,
                              `kind` varchar(32) NOT NULL,
                              `status` varchar(16) NOT NULL DEFAULT 'PENDING',
                              `percent` int NOT NULL DEFAULT 0,
                              `message` text DEFAULT NULL,
                              `error` text DEFAULT NULL,
                              `createdBy` bigint NOT NULL,
                              `createdAt` bigint NOT NULL,
                              `updatedAt` bigint NOT NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_server_task_uuid` (`uuid`),
                              KEY `idx_server_task_server` (`serverId`),
                              KEY `idx_server_task_node` (`nodeId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Long running node jobs.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(serverTask: ServerTask, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`uuid`, `serverId`, `nodeId`, `kind`, `status`, `percent`, `message`, `error`, " +
                    "`createdBy`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverTask.uuid,
                    serverTask.serverId,
                    serverTask.nodeId,
                    serverTask.kind.name,
                    serverTask.status.name,
                    serverTask.percent,
                    serverTask.message,
                    serverTask.error,
                    serverTask.createdBy,
                    serverTask.createdAt,
                    serverTask.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): ServerTask? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getByUuid(uuid: String, sqlClient: SqlClient): ServerTask? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `uuid` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(uuid))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getAllByServerId(serverId: Long, sqlClient: SqlClient): List<ServerTask> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? ORDER BY `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllUnfinished(sqlClient: SqlClient): List<ServerTask> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `status` IN (?, ?) ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(ServerTaskStatus.PENDING.name, ServerTaskStatus.RUNNING.name))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun updateProgressByUuid(
        uuid: String,
        status: ServerTaskStatus,
        percent: Int,
        message: String?,
        error: String?,
        updatedAt: Long,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ?, `percent` = ?, " +
                "`message` = ?, `error` = ?, `updatedAt` = ? WHERE `uuid` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status.name,
                    percent,
                    message,
                    error,
                    updatedAt,
                    uuid
                )
            )
            .coAwait()
    }

    override suspend fun clearServerIdByServerId(serverId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `serverId` = NULL WHERE `serverId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId))
            .coAwait()
    }

    override suspend fun deleteByNodeId(nodeId: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId))
            .coAwait()
    }

    override suspend fun deleteOlderThan(time: Long, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `createdAt` < ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(time))
            .coAwait()
    }
}
