package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.TokenDao
import com.panomc.platform.db.model.Token
import com.panomc.platform.token.TokenType
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class TokenDaoImpl : TokenDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `subject` mediumtext NOT NULL,
                          `token` mediumtext NOT NULL,
                          `type` varchar(32) NOT NULL,
                          `expireDate` bigint(20) NOT NULL,
                          `startDate` bigint NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Valid token table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(token: Token, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`subject`, `token`, `type`, `expireDate`, `startDate`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    token.subject,
                    token.token,
                    token.type.name,
                    token.expireDate,
                    token.startDate
                )
            )
            .await()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun existsByTokenAndType(
        token: String,
        tokenType: TokenType,
        sqlClient: SqlClient
    ): Boolean {
        val query = "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `token` = ? AND `type` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(token, tokenType.name))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteByToken(token: String, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `token` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(token)
            )
            .await()
    }

    override suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `subject` = ? AND `type` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(subject, type.name)
            )
            .await()
    }

    override suspend fun getLastBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlClient: SqlClient
    ): Token? {
        val query =
            "SELECT `id`, `subject`, `token`, `type`, `expireDate`, `startDate` FROM `${getTablePrefix() + tableName}` WHERE `subject` = ? AND `type` = ? order by `expireDate` DESC limit 1"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    subject,
                    type.name
                )
            )
            .await()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }
}