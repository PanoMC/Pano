package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerPlayerDao
import com.panomc.platform.db.model.ServerPlayer
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerPlayerDaoImpl : ServerPlayerDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `uuid` varchar(255) NOT NULL,
                              `username` varchar(255) NOT NULL,
                              `ping` bigint NOT NULL,
                              `serverId` bigint NOT NULL,
                              `loginTime` bigint NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server player table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        serverPlayer: ServerPlayer,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`uuid`, `username`, `ping`, `serverId`, `loginTime`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverPlayer.uuid.toString(),
                    serverPlayer.username,
                    serverPlayer.ping,
                    serverPlayer.serverId,
                    serverPlayer.loginTime
                )
            ).await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun deleteByUsernameAndServerId(username: String, serverId: Long, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `username` = ? AND `serverId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(username, serverId)
            )
            .await()
    }

    override suspend fun existsByUsername(
        username: String,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(username) FROM `${getTablePrefix() + tableName}` where `username` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(username))
            .await()

        return rows.toList()[0].getLong(0) > 0
    }

    override suspend fun deleteByServerId(serverId: Long, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(serverId)
            )
            .await()
    }

}