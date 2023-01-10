package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.ServerDao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
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
                              `motd` text NOT NULL,
                              `host` varchar(255) NOT NULL,
                              `port` int(5) NOT NULL,
                              `player_count` bigint NOT NULL,
                              `max_player_count` bigint NOT NULL,
                              `server_type` varchar(255) NOT NULL,
                              `server_version` varchar(255) NOT NULL,
                              `favicon` text NOT NULL,
                              `permission_granted` int(1) default 0,
                              `status` int(1) NOT NULL,
                              `added_time` bigint NOT NULL,
                              `accepted_time` bigint NOT NULL,
                              `start_time` bigint NOT NULL,
                              `stop_time` bigint NOT NULL,
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
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `motd`, `host`, `port`, `player_count`, `max_player_count`, `server_type`, `server_version`, `favicon`, `status`, `added_time`, `accepted_time`, `start_time`, `stop_time`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    server.name,
                    server.motd,
                    server.host,
                    server.port,
                    server.playerCount,
                    server.maxPlayerCount,
                    server.type,
                    server.version,
                    server.favicon,
                    server.status.value,
                    server.addedTime,
                    0,
                    server.startTime,
                    0,
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlConnection: SqlConnection): Server? {
        val query =
            "SELECT `id`, `name`, `motd`, `host`, `port`, `player_count`, `max_player_count`, `server_type`, `server_version`, `favicon`, `permission_granted`, `status`, `added_time`, `accepted_time`, `start_time`, `stop_time` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

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

    override suspend fun getAllByPermissionGranted(sqlConnection: SqlConnection): List<Server> {
        val query =
            "SELECT `id`, `name`, `motd`, `host`, `port`, `player_count`, `max_player_count`, `server_type`, `server_version`, `favicon`, `permission_granted`, `status`, `added_time`, `accepted_time`, `start_time`, `stop_time` FROM `${getTablePrefix() + tableName}` WHERE  `permission_granted` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .await()

        return Server.from(rows)
    }

    override suspend fun countOfPermissionGranted(sqlConnection: SqlConnection): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE  `permission_granted` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .await()

        return rows.toList()[0].getLong(0)
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

    override suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlConnection: SqlConnection
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `permission_granted` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    if (permissionGranted) 1 else 0,
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

    override suspend fun deleteById(id: Long, sqlConnection: SqlConnection) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()
    }

    override suspend fun updatePlayerCountById(id: Long, playerCount: Int, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `player_count` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    playerCount,
                    id
                )
            )
            .await()
    }

    override suspend fun updateStartTimeById(id: Long, startTime: Long, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `start_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    startTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateStopTimeById(id: Long, stopTime: Long, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `stop_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    stopTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateAcceptedTimeById(id: Long, acceptedTime: Long, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `accepted_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    acceptedTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateServerForOfflineById(id: Long, sqlConnection: SqlConnection) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ?, `player_count` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ServerStatus.OFFLINE.value,
                    0,
                    id
                )
            )
            .await()
    }

    override suspend fun updateById(
        id: Long,
        name: String,
        motd: String,
        host: String,
        port: Int,
        playerCount: Long,
        maxPlayerCount: Long,
        type: ServerType,
        version: String,
        favicon: String,
        status: ServerStatus,
        startTime: Long,
        sqlConnection: SqlConnection
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `motd` = ?, `host` = ?, `port` = ?, `player_count` = ?, `max_player_count` = ?, `server_type` = ?, `server_version` = ?, `favicon` = ?, `status` = ?, `start_time` = ? WHERE `id` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    name,
                    motd,
                    host,
                    port,
                    playerCount,
                    maxPlayerCount,
                    type,
                    version,
                    favicon,
                    status.value,
                    startTime,
                    id
                )
            )
            .await()
    }
}