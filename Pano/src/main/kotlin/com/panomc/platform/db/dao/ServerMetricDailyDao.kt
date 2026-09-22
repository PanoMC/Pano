package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.ServerMetricDaily
import com.panomc.platform.server.dto.PlayerActivity
import io.vertx.sqlclient.SqlClient

abstract class ServerMetricDailyDao : Dao<ServerMetricDaily>(ServerMetricDaily::class.java) {
    /**
     * Folds one recorded minute into [serverId]'s row for [day] (§2.4.24): the peak is the higher of
     * the two, the average a running mean over the minutes counted so far. One statement, whether
     * the row exists yet or not.
     */
    abstract suspend fun record(
        serverId: Long,
        day: Long,
        players: Long,
        sqlClient: SqlClient
    )

    /** [serverId]'s days from [from] on, day's local midnight → peak and average. */
    abstract suspend fun getSince(
        serverId: Long,
        from: Long,
        sqlClient: SqlClient
    ): Map<Long, PlayerActivity>

    /** Drops every day before [day]. */
    abstract suspend fun deleteOlderThan(
        day: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun deleteByServerId(
        serverId: Long,
        sqlClient: SqlClient
    )
}
