package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.TokenDao
import com.panomc.platform.db.model.Token
import com.panomc.platform.token.TokenType
import io.vertx.kotlin.coroutines.await
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

@Dao
class TokenDaoImpl(databaseManager: DatabaseManager) : DaoImpl(databaseManager, "token"), TokenDao {

    override suspend fun init(sqlConnection: SqlConnection) {
        sqlConnection
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `subject` mediumtext NOT NULL,
                          `token` mediumtext NOT NULL,
                          `type` varchar(32) NOT NULL,
                          `expire_date` bigint(20) NOT NULL,
                          `start_date` bigint NOT NULL,
                          PRIMARY KEY (`id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Valid token table.';
                        """
            )
            .execute()
            .await()
    }

    override suspend fun add(token: Token, sqlConnection: SqlConnection): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`subject`, `token`, `type`, `expire_date`, `start_date`) " +
                    "VALUES (?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlConnection
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

    override suspend fun isExistsByTokenAndType(
        token: String,
        tokenType: TokenType,
        sqlConnection: SqlConnection
    ): Boolean {
        val query = "SELECT COUNT(`id`) FROM `${getTablePrefix() + tableName}` WHERE `token` = ? AND `type` = ?"

        val rows: RowSet<Row> = sqlConnection
            .preparedQuery(query)
            .execute(Tuple.of(token, tokenType.name))
            .await()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteByToken(token: String, sqlConnection: SqlConnection) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `token` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(token)
            )
            .await()
    }

    override suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlConnection: SqlConnection) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `subject` = ? AND `type` = ?"

        sqlConnection
            .preparedQuery(query)
            .execute(
                Tuple.of(subject, type.name)
            )
            .await()
    }

    override suspend fun getLastBySubjectAndType(
        subject: String,
        type: TokenType,
        sqlConnection: SqlConnection
    ): Token? {
        val query =
            "SELECT `id`, `subject`, `token`, `type`, `expire_date`, `start_date` FROM `${getTablePrefix() + tableName}` WHERE `subject` = ? AND `type` = ? order by `expire_date` DESC limit 1"

        val rows: RowSet<Row> = sqlConnection
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

        return Token.from(row)
    }
}