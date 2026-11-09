package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Gives a server its own time zone (`server.timeZone`, SM-60, §2.4.25): the IANA id its node or its
 * plugin reports, for the Overview's "Server time" switch. Null until one of them has said.
 */
@Migration
class DatabaseMigration49to50 : DatabaseMigration(
    49,
    50,
    "Add the time zone to servers"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addTimeZone()
    )

    private fun addTimeZone(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // Skipped when the column is there, so a migration that was interrupted is safe to run again.
        addMissingColumns(sqlClient, "${getTablePrefix()}server", "timeZone" to "varchar(64) DEFAULT NULL")
    }
}
