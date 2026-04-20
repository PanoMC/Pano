package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.OnlinePlayerHistoryDao
import com.panomc.platform.db.model.OnlinePlayerHistory
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class OnlinePlayerHistoryDaoImpl : OnlinePlayerHistoryDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                        CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                          `id` bigint NOT NULL AUTO_INCREMENT,
                          `date` BIGINT(20) NOT NULL,
                          `maxCount` BIGINT NOT NULL DEFAULT 0,
                          `sumCount` BIGINT NOT NULL DEFAULT 0,
                          `sampleCount` BIGINT NOT NULL DEFAULT 0,
                          `lastRecordedAt` BIGINT(20) NOT NULL,
                          PRIMARY KEY (`id`),
                          UNIQUE KEY `uq_online_player_history_date` (`date`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily online player snapshot history.';
                    """
            )
            .execute()
            .coAwait()
    }

    override suspend fun recordSample(
        date: Long,
        count: Long,
        sqlClient: SqlClient
    ) {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`date`, `maxCount`, `sumCount`, `sampleCount`, `lastRecordedAt`) " +
                    "VALUES (?, ?, ?, 1, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "`maxCount` = GREATEST(`maxCount`, VALUES(`maxCount`)), " +
                    "`sumCount` = `sumCount` + VALUES(`maxCount`), " +
                    "`sampleCount` = `sampleCount` + 1, " +
                    "`lastRecordedAt` = VALUES(`lastRecordedAt`)"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    date,
                    count,
                    count,
                    System.currentTimeMillis()
                )
            )
            .coAwait()
    }

    override suspend fun getByDate(
        date: Long,
        sqlClient: SqlClient
    ): OnlinePlayerHistory? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `date` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(date))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun getByTimeRange(
        from: Long,
        to: Long,
        sqlClient: SqlClient
    ): List<OnlinePlayerHistory> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                    "WHERE `date` >= ? AND `date` < ? ORDER BY `date` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(from, to))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun deleteOlderThan(
        time: Long,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `date` < ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(time))
            .coAwait()
    }
}
