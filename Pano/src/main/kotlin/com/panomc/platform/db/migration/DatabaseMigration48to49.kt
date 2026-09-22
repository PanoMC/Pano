package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.db.implementation.ServerMetricDailyDaoImpl
import com.panomc.platform.util.TimeUtil
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.util.TimeZone

/**
 * Adds the per-day player rollup behind the Server Activity Year view (SM-59, §2.4.24) and fills it
 * from the thirty days of minutes `server_metric` still holds, so the year starts with a month of
 * history rather than empty.
 */
@Migration
class DatabaseMigration48to49 : DatabaseMigration(
    48,
    49,
    "Add the daily server player rollup"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        createTable(),
        backfill()
    )

    private fun createTable(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        sqlClient
            .query(ServerMetricDailyDaoImpl.createTableQuery("${getTablePrefix()}server_metric_daily"))
            .execute()
            .coAwait()
    }

    /**
     * Groups the stored minutes by local day exactly as the Week/Month chart does — a fixed offset
     * in SQL, then each day snapped to its real local midnight — and writes one row per server per
     * day. Idempotent: running it again overwrites the rows with the same figures.
     */
    private fun backfill(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

        val rows = sqlClient
            .preparedQuery(
                "SELECT `serverId`, FLOOR((`ts` + ?) / $DAY_MILLIS) AS `day`, " +
                        "MAX(`players`) AS `peak`, AVG(`players`) AS `average`, COUNT(*) AS `samples` " +
                        "FROM `${getTablePrefix()}server_metric` GROUP BY `serverId`, `day`"
            )
            .execute(Tuple.of(offset))
            .coAwait()

        val days = rows.map { row ->
            // Back to a millisecond, then onto the day's real midnight: a fixed offset is an hour
            // off on the days the clocks moved, and the recorder keys its rows by the real one.
            val approximate = (row.getLong("day") ?: 0L) * DAY_MILLIS - offset

            Day(
                serverId = row.getLong("serverId") ?: 0L,
                day = TimeUtil.startOfDay(approximate + DAY_MILLIS / 2),
                peak = row.getNumeric("peak")?.toLong() ?: 0L,
                average = row.getNumeric("average")?.toDouble() ?: 0.0,
                samples = row.getLong("samples") ?: 0L
            )
        }

        // Two groups can land on the same real day around a clock change; they are one day, so they
        // become one row rather than one overwriting the other.
        val batch = days
            .groupBy { it.serverId to it.day }
            .map { (key, parts) ->
                val samples = parts.sumOf { it.samples }
                val average = if (samples > 0) parts.sumOf { it.average * it.samples } / samples else 0.0

                Tuple.of(key.first, key.second, parts.maxOf { it.peak }, average, samples)
            }

        if (batch.isNotEmpty()) {
            sqlClient
                .preparedQuery(
                    "INSERT INTO `${getTablePrefix()}server_metric_daily` " +
                            "(`serverId`, `day`, `peakPlayers`, `avgPlayers`, `samples`) VALUES (?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE `peakPlayers` = VALUES(`peakPlayers`), " +
                            "`avgPlayers` = VALUES(`avgPlayers`), `samples` = VALUES(`samples`)"
                )
                .executeBatch(batch)
                .coAwait()
        }
    }

    private data class Day(val serverId: Long, val day: Long, val peak: Long, val average: Double, val samples: Long)

    companion object {
        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
