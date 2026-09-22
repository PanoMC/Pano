package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Adds the two flags a node reports about an adopted process (SM-51, §2.4.16).
 *
 * They are columns rather than in-memory state because every panel that shows a server reads the
 * row: the header hint and the console's input box both have to be right on a plain page load,
 * not only for whoever happened to be watching when the node connected. The defaults are what
 * every existing row means -- not adopted, console input available -- so nothing has to be
 * backfilled and a linked server is unaffected.
 */
@Migration
class DatabaseMigration42to43 : DatabaseMigration(
    42,
    43,
    "Add adopted and stdinAvailable to the server table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addAdopted(),
        addStdinAvailable()
    )

    private fun addAdopted(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        addColumn(sqlClient, "adopted", "TINYINT(1) NOT NULL DEFAULT 0")
    }

    private fun addStdinAvailable(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        addColumn(sqlClient, "stdinAvailable", "TINYINT(1) NOT NULL DEFAULT 1")
    }

    /**
     * Adds one column unless it is already there.
     *
     * `IF NOT EXISTS` is MariaDB's, which is what Pano runs on, and it is what makes a migration
     * that was interrupted between its two handlers safe to run again.
     */
    private suspend fun addColumn(sqlClient: SqlClient, name: String, definition: String) {
        val query = "ALTER TABLE `${getTablePrefix()}server` ADD COLUMN IF NOT EXISTS `$name` $definition"

        sqlClient.preparedQuery(query).execute().coAwait()
    }
}
