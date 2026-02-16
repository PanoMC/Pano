package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.BanHistoryDao
import com.panomc.platform.db.model.BanHistory
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class BanHistoryDaoImpl : BanHistoryDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `userId` bigint,
                              `reason` varchar(255),
                              `emailNotified` tinyint(1),
                              `bannedUntil` bigint,
                              `bannedBy` varchar(255),
                              `bannedBySystem` tinyint(1) DEFAULT 0,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ban history table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        banHistory: BanHistory,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`userId`, `reason`, `emailNotified`, `bannedUntil`, `bannedBy`, `bannedBySystem`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    banHistory.userId,
                    banHistory.reason,
                    banHistory.emailNotified,
                    banHistory.bannedUntil,
                    banHistory.bannedBy,
                    banHistory.bannedBySystem,
                    banHistory.createdAt,
                    banHistory.updatedAt,
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun countByUserId(
        id: Long,
        sqlClient: SqlClient
    ): Long {
        val query =
            "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where userId = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByUserIdAndPage(
        userId: Long,
        page: Long,
        sqlClient: SqlClient
    ): List<BanHistory> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE userId = ? ORDER BY `updatedAt` DESC, `createdAt` DESC LIMIT 10 ${if (page == 1L) "" else "OFFSET ${(page - 1) * 10}"}"

        val parameters = Tuple.tuple()

        parameters.addLong(userId)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(parameters)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteByUserId(
        userId: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "DELETE FROM `${getTablePrefix() + tableName}` WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    userId
                )
            )
            .coAwait()
    }
}