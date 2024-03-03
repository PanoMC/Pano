package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerDao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerDaoImpl : ServerDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` varchar(255) NOT NULL,
                              `motd` text NOT NULL,
                              `host` varchar(255) NOT NULL,
                              `port` int(5) NOT NULL,
                              `playerCount` bigint NOT NULL,
                              `maxPlayerCount` bigint NOT NULL,
                              `serverType` varchar(255) NOT NULL,
                              `serverVersion` varchar(255) NOT NULL,
                              `favicon` text NOT NULL,
                              `permissionGranted` TINYINT(1) default 0,
                              `status` VARCHAR(255) NOT NULL,
                              `addedTime` bigint NOT NULL,
                              `acceptedTime` bigint NOT NULL,
                              `startTime` bigint NOT NULL,
                              `stopTime` bigint NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        server: Server,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `motd`, `host`, `port`, `playerCount`, `maxPlayerCount`, `serverType`, `serverVersion`, `favicon`, `status`, `addedTime`, `acceptedTime`, `startTime`, `stopTime`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
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
                    server.status.name,
                    server.addedTime,
                    0,
                    server.startTime,
                    0,
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): Server? {
        val query =
            "SELECT `id`, `name`, `motd`, `host`, `port`, `playerCount`, `maxPlayerCount`, `serverType`, `serverVersion`, `favicon`, `permissionGranted`, `status`, `addedTime`, `acceptedTime`, `startTime`, `stopTime` FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlClient
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

        return row.toEntity()
    }

    override suspend fun getAllByPermissionGranted(sqlClient: SqlClient): List<Server> {
        val query =
            "SELECT `id`, `name`, `motd`, `host`, `port`, `playerCount`, `maxPlayerCount`, `serverType`, `serverVersion`, `favicon`, `permissionGranted`, `status`, `addedTime`, `acceptedTime`, `startTime`, `stopTime` FROM `${getTablePrefix() + tableName}` WHERE  `permissionGranted` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .await()

        return rows.toEntities()
    }

    override suspend fun countOfPermissionGranted(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE  `permissionGranted` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .await()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun updateStatusById(id: Long, status: ServerStatus, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status.name,
                    id
                )
            )
            .await()
    }

    override suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `permissionGranted` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    if (permissionGranted) 1 else 0,
                    id
                )
            )
            .await()
    }

    override suspend fun existsById(id: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .await()
    }

    override suspend fun updatePlayerCountById(id: Long, playerCount: Int, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `playerCount` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    playerCount,
                    id
                )
            )
            .await()
    }

    override suspend fun updateStartTimeById(id: Long, startTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `startTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    startTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateStopTimeById(id: Long, stopTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `stopTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    stopTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateAcceptedTimeById(id: Long, acceptedTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `acceptedTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    acceptedTime,
                    id
                )
            )
            .await()
    }

    override suspend fun updateServerForOfflineById(id: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ?, `playerCount` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ServerStatus.OFFLINE.name,
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
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `motd` = ?, `host` = ?, `port` = ?, `playerCount` = ?, `maxPlayerCount` = ?, `serverType` = ?, `serverVersion` = ?, `favicon` = ?, `status` = ?, `startTime` = ? WHERE `id` = ?"

        sqlClient
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
                    status.name,
                    startTime,
                    id
                )
            )
            .await()
    }
}