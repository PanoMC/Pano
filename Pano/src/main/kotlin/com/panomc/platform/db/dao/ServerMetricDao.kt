package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerMetric
import com.panomc.platform.server.dto.PlayerActivity
import io.vertx.sqlclient.SqlClient

abstract class ServerMetricDao : Dao<ServerMetric>(ServerMetric::class.java) {
    abstract suspend fun add(
        serverMetric: ServerMetric,
        sqlClient: SqlClient
    ): Long

    /**
     * Averages the stored minutes of [serverId] between [from] and [to] into buckets of
     * [bucketMs] milliseconds, oldest first.
     *
     * Aggregating in SQL keeps a 30 day chart at a few hundred points instead of 43 200 rows on
     * the wire. Returned rows carry `id = -1` and `ts` = the start of the bucket.
     */
    abstract suspend fun getSeries(
        serverId: Long,
        from: Long,
        to: Long,
        bucketMs: Long,
        sqlClient: SqlClient,
        /**
         * Shift applied before bucketing, so buckets start at local rather than UTC boundaries —
         * Pano's zone offset for day buckets, 0 (the keys as they always were) for everything else.
         */
        offsetMs: Long = 0L
    ): List<ServerMetric>

    /**
     * The same buckets as [getSeries], for every id in [serverIds] at once, keyed by server id
     * (§2.4.18 B).
     *
     * One statement for the whole list: the servers modal draws a sparkline on every card and
     * re-asks while it is open, so a query per server would be a round trip per card per refresh.
     * A server with no rows in the window is simply absent from the map rather than present with
     * an empty list.
     */
    abstract suspend fun getSeriesForServers(
        serverIds: List<Long>,
        from: Long,
        to: Long,
        bucketMs: Long,
        sqlClient: SqlClient,
        /** See [getSeries]. */
        offsetMs: Long = 0L
    ): Map<Long, List<ServerMetric>>

    /**
     * Peak and average players per local calendar day since [from], for the Server Activity chart
     * (§2.4.19).
     *
     * [tzOffsetMs] is the offset a day is measured in: rows are stored in UTC milliseconds and the
     * chart is drawn in the operator's days, so the grouping shifts the timestamp before dividing
     * it. One grouped query rather than a query per day — a month view would otherwise be 31 round
     * trips to draw one card. Days with no rows are simply absent; filling them is the caller's
     * job, because only the caller knows which days the chart asked about.
     */
    abstract suspend fun getDailyPlayerActivity(
        serverId: Long,
        from: Long,
        tzOffsetMs: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity>

    /**
     * Peak and average players per [bucketMs] since [from], keyed by bucket start (§2.4.26): the
     * Server Activity chart's Hour (minute buckets) and Day (hour buckets) views. One grouped query;
     * empty buckets are absent and filled by the caller.
     */
    abstract suspend fun getPlayerActivity(
        serverId: Long,
        from: Long,
        bucketMs: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity>

    abstract suspend fun deleteOlderThan(
        time: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )
}
