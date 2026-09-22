package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerMetricDailyDao
import com.panomc.platform.server.dto.PlayerActivity
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerMetricDailyDaoImpl : ServerMetricDailyDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(createTableQuery(getTablePrefix() + tableName))
            .execute()
            .coAwait()
    }

    override suspend fun record(
        serverId: Long,
        day: Long,
        players: Long,
        sqlClient: SqlClient
    ) {
        sqlClient
            .preparedQuery(recordQuery(getTablePrefix() + tableName))
            .execute(Tuple.of(serverId, day, players, players.toDouble()))
            .coAwait()
    }

    override suspend fun getSince(
        serverId: Long,
        from: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity> {
        val query = "SELECT `day`, `peakPlayers`, `avgPlayers` FROM `${getTablePrefix() + tableName}` " +
                "WHERE `serverId` = ? AND `day` >= ? AND `samples` > 0 ORDER BY `day` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId, from))
            .coAwait()

        return rows.associate { row ->
            (row.getLong("day") ?: 0L) to PlayerActivity(
                peak = row.getLong("peakPlayers") ?: 0L,
                average = row.getDouble("avgPlayers") ?: 0.0
            )
        }
    }

    override suspend fun deleteOlderThan(
        day: Long,
        sqlClient: SqlClient
    ) {
        sqlClient
            .preparedQuery("DELETE FROM `${getTablePrefix() + tableName}` WHERE `day` < ?")
            .execute(Tuple.of(day))
            .coAwait()
    }

    override suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    ) {
        sqlClient
            .preparedQuery("DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?")
            .execute(Tuple.of(serverId))
            .coAwait()
    }

    companion object {
        /**
         * The table, shared with `DatabaseMigration48to49` so an upgraded install and a fresh one
         * end up with the same one.
         */
        fun createTableQuery(table: String): String = """
            CREATE TABLE IF NOT EXISTS `$table` (
              `id` bigint NOT NULL AUTO_INCREMENT,
              `serverId` bigint NOT NULL,
              `day` bigint NOT NULL,
              `peakPlayers` bigint NOT NULL DEFAULT 0,
              `avgPlayers` double NOT NULL DEFAULT 0,
              `samples` bigint NOT NULL DEFAULT 0,
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_server_metric_daily_server_day` (`serverId`, `day`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per day server player history.';
        """.trimIndent()

        /**
         * One minute folded into its day, in one statement (§2.4.24).
         *
         * MariaDB applies the assignments of `ON DUPLICATE KEY UPDATE` left to right, each seeing
         * the ones before it, so the average is worked out from the old `samples` *before* the
         * count moves on: `avg = (avg * samples + x) / (samples + 1)`. A new row starts at the
         * minute itself with one sample.
         */
        fun recordQuery(table: String): String =
            "INSERT INTO `$table` (`serverId`, `day`, `peakPlayers`, `avgPlayers`, `samples`) " +
                    "VALUES (?, ?, ?, ?, 1) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "`avgPlayers` = (`avgPlayers` * `samples` + VALUES(`avgPlayers`)) / (`samples` + 1), " +
                    "`peakPlayers` = GREATEST(`peakPlayers`, VALUES(`peakPlayers`)), " +
                    "`samples` = `samples` + 1"
    }
}
