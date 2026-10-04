package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Keeps the server process' resident memory in the per-minute history (`server_metric.memRss`).
 *
 * A server's memory setting is the whole process now, not only the JVM heap, so the panel draws the
 * process against it; a plugin row only had the heap. Existing rows keep null, which reads as "not
 * measured" for the minutes before this version -- node rows already carry the process in
 * `memUsed`, and the panel falls back to that.
 */
@Migration
class DatabaseMigration53to54 : DatabaseMigration(
    53,
    54,
    "Add the process' resident memory to the per-minute server metrics"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            // IF NOT EXISTS is MariaDB's, and it is what makes a migration that was interrupted
            // safe to run again.
            sqlClient
                .preparedQuery(
                    "ALTER TABLE `${getTablePrefix()}server_metric` ADD COLUMN IF NOT EXISTS `memRss` bigint DEFAULT NULL"
                )
                .execute()
                .coAwait()
        }
    )
}
