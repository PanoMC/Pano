package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Gives the per-minute history somewhere to keep how much disk a server takes (SM-53, §2.4.18).
 *
 * The size of a server's directory is the third vital sign next to CPU and memory, and unlike
 * those two it is not something either reporter can produce for free: it is a walk of the whole
 * directory, so it arrives every few minutes at best and is missing entirely until the first walk
 * lands. Nullable is therefore the whole point of the column — a row written before anybody
 * measured the directory must say "not measured" rather than claim the server takes no space.
 */
@Migration
class DatabaseMigration44to45 : DatabaseMigration(
    44,
    45,
    "Add disk usage to servers and their metrics"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addMetricDiskUsed(),
        addServerDiskUsed(),
        addServerDiskTotal()
    )

    private fun addMetricDiskUsed(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // IF NOT EXISTS is MariaDB's, which is what Pano runs on, and it is what makes a migration
        // interrupted between its handlers safe to run again.
        execute(
            sqlClient,
            "ALTER TABLE `${getTablePrefix()}server_metric` " +
                "ADD COLUMN IF NOT EXISTS `diskUsed` BIGINT DEFAULT NULL"
        )
    }

    /**
     * The same figure on the server row itself, which is what makes it survive.
     *
     * The history table only has rows for the minutes a server was reporting, and the live sample
     * is in memory: switch a server off, restart Pano, and the last thing anybody knew about its
     * forty gigabytes would be gone even though the directory never moved. The row is where a
     * fact about files belongs.
     */
    private fun addServerDiskUsed(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        execute(
            sqlClient,
            "ALTER TABLE `${getTablePrefix()}server` " +
                "ADD COLUMN IF NOT EXISTS `diskUsed` BIGINT DEFAULT NULL"
        )
    }

    /**
     * The disk that directory is on, which is the other half of the reading.
     *
     * Not in the history table with the rest: the size of a partition is a fact about the host
     * this minute and every other one, and a column of the same number repeated 43 200 times a
     * month would buy nothing the row cannot say.
     */
    private fun addServerDiskTotal(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        execute(
            sqlClient,
            "ALTER TABLE `${getTablePrefix()}server` " +
                "ADD COLUMN IF NOT EXISTS `diskTotal` BIGINT DEFAULT NULL"
        )
    }

    private suspend fun execute(sqlClient: SqlClient, query: String) {
        sqlClient.preparedQuery(query).execute().coAwait()
    }
}
