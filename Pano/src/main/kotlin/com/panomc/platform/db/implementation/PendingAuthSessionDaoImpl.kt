package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.PendingAuthSessionDao
import com.panomc.platform.db.model.PendingAuthSession
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class PendingAuthSessionDaoImpl : PendingAuthSessionDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `token` varchar(128) NOT NULL,
                          `userId` bigint NOT NULL,
                          `source` varchar(64) NOT NULL,
                          `createdAt` bigint(20) NOT NULL,
                          `expiresAt` bigint(20) NOT NULL,
                          PRIMARY KEY (`id`),
                          UNIQUE KEY `uq_token` (`token`),
                          KEY `idx_expires_at` (`expiresAt`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Pending auth completion handles (step-up, social, magic-link, …).';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(session: PendingAuthSession, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`token`, `userId`, `source`, `createdAt`, `expiresAt`) " +
                "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    session.token,
                    session.userId,
                    session.source,
                    session.createdAt,
                    session.expiresAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getByToken(token: String, sqlClient: SqlClient): PendingAuthSession? {
        val query =
            "SELECT `id`, `token`, `userId`, `source`, `createdAt`, `expiresAt` FROM `${getTablePrefix() + tableName}` WHERE `token` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(token))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun deleteByToken(token: String, sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `token` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(token))
            .coAwait()
    }

    override suspend fun deleteExpired(sqlClient: SqlClient) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `expiresAt` < ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(System.currentTimeMillis()))
            .coAwait()
    }
}
