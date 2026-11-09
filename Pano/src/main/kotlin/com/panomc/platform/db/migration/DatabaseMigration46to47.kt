package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Gives the per-minute history somewhere to keep network traffic (SM-57, §2.4.22 A).
 *
 * Bytes per second received and sent, nullable for the same reason disk is: a minute nobody could
 * measure — the first tick after a start, a host that is not Linux, a linked server with no node —
 * must say "not measured" rather than claim a server that sent nothing.
 */
@Migration
class DatabaseMigration46to47 : DatabaseMigration(
    46,
    47,
    "Add network traffic to server metrics"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addColumn("netRx"),
        addColumn("netTx")
    )

    private fun addColumn(name: String): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // Skipped when the column is there: a migration interrupted between its two handlers is safe
        // to run again.
        addMissingColumns(sqlClient, "${getTablePrefix()}server_metric", name to "BIGINT DEFAULT NULL")
    }
}
