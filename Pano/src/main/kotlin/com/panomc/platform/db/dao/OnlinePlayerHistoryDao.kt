package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.OnlinePlayerHistory
import io.vertx.sqlclient.SqlClient

abstract class OnlinePlayerHistoryDao : Dao<OnlinePlayerHistory>(OnlinePlayerHistory::class.java) {

    /**
     * Records a new sample of the online player count for the given day.
     * If a row for the same date already exists, updates the running max / sum / sample count.
     */
    abstract suspend fun recordSample(
        date: Long,
        count: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun getByDate(
        date: Long,
        sqlClient: SqlClient
    ): OnlinePlayerHistory?

    abstract suspend fun getByTimeRange(
        from: Long,
        to: Long,
        sqlClient: SqlClient
    ): List<OnlinePlayerHistory>

    /**
     * Deletes rows older than the given timestamp, used for retention/cleanup.
     */
    abstract suspend fun deleteOlderThan(
        time: Long,
        sqlClient: SqlClient
    )
}
