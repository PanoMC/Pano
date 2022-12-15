package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.ServerDao
import com.panomc.platform.db.model.Server
import com.panomc.platform.util.ServerStatus
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class ServerDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "server"), ServerDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` varchar(255) NOT NULL,
                              `player_count` bigint NOT NULL,
                              `max_player_count` bigint NOT NULL,
                              `server_type` varchar(255) NOT NULL,
                              `server_version` varchar(255) NOT NULL,
                              `favicon` text NOT NULL,
                              `permission_granted` int(1) default 0,
                              `status` int(1) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        server: Server,
        sqlConnection: SqlConnection
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (name, player_count, max_player_count, server_type, server_version, favicon, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    server.name,
                    server.playerCount,
                    server.maxPlayerCount,
                    server.type,
                    server.version,
                    server.favicon,
                    server.status.value
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlConnection: SqlConnection): Server? {
        val query =
            "SELECT `id`, `name`, `player_count`, `max_player_count`, `server_type`, `server_version`, `favicon`, `permission_granted`, `status` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return Server.from(row)
    }

    override suspend fun count(sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun updateStatusById(id: Long, status: ServerStatus, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status.value,
                    id
                )
            )
            .await()
    }

    override suspend fun existsById(id: Long, sqlConnection: SqlConnection): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }
}