package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.ServerDao
import com.panomc.platform.db.model.Server
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.util.*

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
                              `secret_key` text NOT NULL,
                              `public_key` text NOT NULL,
                              `token` text NOT NULL,
                              `permission_granted` int(1) default 0,
                              `status` varchar(255) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Connected server table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(
        server: Server,
        sqlConnection: SqlConnection
    ): String {
        val key = Keys.keyPairFor(SignatureAlgorithm.RS256)

        val token = Jwts.builder()
            .setSubject("SERVER_CONNECT")
            .signWith(
                key.private
            )
            .compact()

        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (name, player_count, max_player_count, server_type, server_version, favicon, secret_key, public_key, token, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    server.name,
                    server.playerCount,
                    server.maxPlayerCount,
                    server.type,
                    server.version,
                    server.favicon,
                    Base64.getEncoder().encodeToString(key.private.encoded),
                    Base64.getEncoder().encodeToString(key.public.encoded),
                    token,
                    server.status
                )
            ).await()

        return token
    }
}