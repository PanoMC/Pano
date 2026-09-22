package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerMetricDao
import com.panomc.platform.db.model.ServerMetric
import com.panomc.platform.server.dto.PlayerActivity
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerMetricDaoImpl : ServerMetricDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `serverId` bigint NOT NULL,
                              `ts` bigint NOT NULL,
                              `tps` double DEFAULT NULL,
                              `mspt` double DEFAULT NULL,
                              `memUsed` bigint NOT NULL DEFAULT 0,
                              `memMax` bigint NOT NULL DEFAULT 0,
                              `cpu` double DEFAULT NULL,
                              `players` bigint NOT NULL DEFAULT 0,
                              `source` varchar(16) NOT NULL DEFAULT 'plugin',
                              `diskUsed` bigint DEFAULT NULL,
                              `netRx` bigint DEFAULT NULL,
                              `netTx` bigint DEFAULT NULL,
                              PRIMARY KEY (`id`),
                              KEY `idx_server_metric_server_ts` (`serverId`, `ts`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per minute server performance history.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        serverMetric: ServerMetric,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` " +
                    "(`serverId`, `ts`, `tps`, `mspt`, `memUsed`, `memMax`, `cpu`, `players`, `source`, " +
                    "`diskUsed`, `netRx`, `netTx`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverMetric.serverId,
                    serverMetric.ts,
                    serverMetric.tps,
                    serverMetric.mspt,
                    serverMetric.memUsed,
                    serverMetric.memMax,
                    serverMetric.cpu,
                    serverMetric.players,
                    serverMetric.source,
                    serverMetric.diskUsed,
                    serverMetric.netRx,
                    serverMetric.netTx
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getSeries(
        serverId: Long,
        from: Long,
        to: Long,
        bucketMs: Long,
        sqlClient: SqlClient,
        offsetMs: Long
    ): List<ServerMetric> {
        val query =
            "SELECT $BUCKET, $AGGREGATES " +
                    "FROM `${getTablePrefix() + tableName}` " +
                    "WHERE `serverId` = ? AND `ts` >= ? AND `ts` <= ? " +
                    "GROUP BY `bucket` ORDER BY `bucket` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(offsetMs, bucketMs, bucketMs, offsetMs, serverId, from, to))
            .coAwait()

        return rows.map { row -> toMetric(serverId, row) }
    }

    override suspend fun getSeriesForServers(
        serverIds: List<Long>,
        from: Long,
        to: Long,
        bucketMs: Long,
        sqlClient: SqlClient,
        offsetMs: Long
    ): Map<Long, List<ServerMetric>> {
        // `IN ()` is a syntax error rather than an empty result, and a panel with no servers on
        // it is the ordinary first-run case.
        if (serverIds.isEmpty()) {
            return emptyMap()
        }

        val ids = serverIds.distinct()

        val parameters = Tuple.of(offsetMs, bucketMs, bucketMs, offsetMs)

        ids.forEach { parameters.addLong(it) }

        parameters.addLong(from)
        parameters.addLong(to)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(seriesForServersQuery(getTablePrefix() + tableName, ids.size))
            .execute(parameters)
            .coAwait()

        // The rows arrive ordered by server and then by bucket, and groupBy keeps that order, so
        // every server's series comes out oldest first exactly as the single-server one does.
        return rows.groupBy(
            { row -> row.getLong("serverId") ?: -1L },
            { row -> toMetric(row.getLong("serverId") ?: -1L, row) }
        )
    }

    override suspend fun getDailyPlayerActivity(
        serverId: Long,
        from: Long,
        tzOffsetMs: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(dailyPlayerActivityQuery(getTablePrefix() + tableName))
            .execute(Tuple.of(tzOffsetMs, serverId, from))
            .coAwait()

        return rows.associate { row ->
            // Back from "which local day is this" to the millisecond that day started at, which is
            // the key the statistics maps use and the one the chart plots against.
            val day = row.getLong("day") ?: 0L

            (day * DAY_MILLIS - tzOffsetMs) to PlayerActivity(
                peak = row.getLong("peak") ?: 0L,
                average = row.getDouble("average") ?: 0.0
            )
        }
    }

    override suspend fun getPlayerActivity(
        serverId: Long,
        from: Long,
        bucketMs: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(playerActivityQuery(getTablePrefix() + tableName))
            .execute(Tuple.of(bucketMs, bucketMs, serverId, from))
            .coAwait()

        return rows.associate { row ->
            (row.getLong("bucket") ?: 0L) to PlayerActivity(
                peak = row.getLong("peak") ?: 0L,
                average = row.getDouble("average") ?: 0.0
            )
        }
    }

    override suspend fun deleteOlderThan(
        time: Long,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `ts` < ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(time))
            .coAwait()
    }

    override suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    ) {
        val query = "DELETE FROM `${getTablePrefix() + tableName}` WHERE `serverId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(serverId))
            .coAwait()
    }

    private fun toMetric(serverId: Long, row: Row) = ServerMetric(
        serverId = serverId,
        ts = row.getLong("bucket") ?: 0,
        tps = row.getDouble("tps"),
        mspt = row.getDouble("mspt"),
        memUsed = row.getLong("memUsed") ?: 0,
        memMax = row.getLong("memMax") ?: 0,
        cpu = row.getDouble("cpu"),
        players = row.getLong("players") ?: 0,
        source = row.getString("source") ?: ServerMetric.SOURCE_PLUGIN,
        // Null all the way through when nothing in the bucket was measured: the panel draws a gap
        // in the disk line rather than a server that shrank to nothing.
        diskUsed = row.getLong("diskUsed"),
        netRx = row.getLong("netRx"),
        netTx = row.getLong("netTx")
    )

    companion object {
        /**
         * The start of the bucket a row falls in: `FLOOR((ts + offset) / size) * size - offset`
         * (§2.4.25). With a zero offset that is the plain `FLOOR(ts / size) * size` it always was;
         * with Pano's zone offset a day bucket starts at local midnight instead of UTC's.
         */
        private const val BUCKET = "FLOOR((`ts` + ?) / ?) * ? - ? AS `bucket`"

        /**
         * What one bucket is, shared by the single-server and the bulk query so the two cannot
         * drift into meaning different things.
         *
         * CAST(... AS SIGNED) keeps the byte counters integral: AVG over a BIGINT column comes
         * back as a decimal, which would otherwise arrive as a Numeric and make the JSON shape
         * depend on the range that was asked for. Disk is a MAX rather than an AVG because it is
         * measured every few minutes at best (§2.4.18 A), so most minutes in a bucket carry no
         * figure and averaging would drag the line down towards zero.
         */
        private const val AGGREGATES =
            "AVG(`tps`) AS `tps`, " +
                    "AVG(`mspt`) AS `mspt`, " +
                    "CAST(AVG(`memUsed`) AS SIGNED) AS `memUsed`, " +
                    "CAST(MAX(`memMax`) AS SIGNED) AS `memMax`, " +
                    "AVG(`cpu`) AS `cpu`, " +
                    "CAST(AVG(`players`) AS SIGNED) AS `players`, " +
                    "CAST(MAX(`diskUsed`) AS SIGNED) AS `diskUsed`, " +
                    // Rates, so the bucket's average; cast like the other byte figures so the JSON
                    // is an integer whatever range was asked for.
                    "CAST(AVG(`netRx`) AS SIGNED) AS `netRx`, " +
                    "CAST(AVG(`netTx`) AS SIGNED) AS `netTx`, " +
                    // One name when every row in the bucket agrees, and `mixed` when they do not,
                    // which is how a chart can say where a week of figures actually came from.
                    "IF(MIN(`source`) = MAX(`source`), MIN(`source`), 'mixed') AS `source`"

        private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

        /**
         * Peak and average players per minute or hour, in one grouped statement (§2.4.26).
         *
         * Plain `FLOOR(ts / size)` buckets keyed by their start. A minute starts at the same instant
         * in every zone; so does an hour in every whole-hour zone, and in a half-hour one (India,
         * Nepal) the hour buckets simply start at :30 local — still one hour of data per point.
         */
        fun playerActivityQuery(table: String): String =
            "SELECT FLOOR(`ts` / ?) * ? AS `bucket`, " +
                    "CAST(MAX(`players`) AS SIGNED) AS `peak`, " +
                    "AVG(`players`) AS `average` " +
                    "FROM `$table` " +
                    "WHERE `serverId` = ? AND `ts` >= ? " +
                    "GROUP BY `bucket` ORDER BY `bucket` ASC"

        /**
         * A local day's peak and average players, in one grouped statement (§2.4.19).
         *
         * The offset is a parameter like everything else, and it is added to the timestamp rather
         * than subtracted from the boundary: `FLOOR((ts + offset) / 86400000)` is the number of
         * the local day a UTC millisecond falls in, which is exactly what a `GROUP BY` needs. The
         * peak is cast because MAX over a BIGINT keeps the column's type and the average is left
         * as a decimal, which is what an average of players is.
         */
        fun dailyPlayerActivityQuery(table: String): String =
            "SELECT FLOOR((`ts` + ?) / $DAY_MILLIS) AS `day`, " +
                    "CAST(MAX(`players`) AS SIGNED) AS `peak`, " +
                    "AVG(`players`) AS `average` " +
                    "FROM `$table` " +
                    "WHERE `serverId` = ? AND `ts` >= ? " +
                    "GROUP BY `day` ORDER BY `day` ASC"

        /**
         * Every listed server's buckets in one statement (§2.4.18 B).
         *
         * One query rather than one per server: the servers modal asks for all of them at once
         * and re-asks every fifteen seconds while it is open, so a loop here would be a round trip
         * per card per refresh. The ids are placeholders like every other value — a panel is
         * allowed to ask about servers it can see, never to write SQL.
         */
        fun seriesForServersQuery(table: String, serverCount: Int): String {
            val placeholders = List(serverCount) { "?" }.joinToString(", ")

            return "SELECT `serverId`, $BUCKET, $AGGREGATES " +
                    "FROM `$table` " +
                    "WHERE `serverId` IN ($placeholders) AND `ts` >= ? AND `ts` <= ? " +
                    "GROUP BY `serverId`, `bucket` ORDER BY `serverId` ASC, `bucket` ASC"
        }
    }
}
