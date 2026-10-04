package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Gives a managed server a start time of its own (`server.processStartedAt`).
 *
 * `startTime` is what the Pano plugin reports when it connects, so a managed server with no plugin in
 * it had no uptime anywhere. This one comes from the node — the process's own start — and is null
 * whenever no process is running.
 */
@Migration
class DatabaseMigration47to48 : DatabaseMigration(
    47,
    48,
    "Add the process start time to managed servers"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addProcessStartedAt()
    )

    private fun addProcessStartedAt(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // IF NOT EXISTS is MariaDB's, and it is what makes a migration that was interrupted safe to
        // run again.
        sqlClient
            .preparedQuery(
                "ALTER TABLE `${getTablePrefix()}server` ADD COLUMN IF NOT EXISTS `processStartedAt` BIGINT DEFAULT NULL"
            )
            .execute()
            .coAwait()
    }
}
