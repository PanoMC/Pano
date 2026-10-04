package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Removes proxy networks from Pano (decided 2026-09-23).
 *
 * Pano no longer creates or groups proxy networks, so the two columns that recorded which network a
 * server belonged to and what it did there have nothing left to say. Existing rows simply become the
 * standalone servers they already were to their node: nothing in the server's directory is touched,
 * so a proxy and its backends keep running exactly as configured, they are just no longer grouped
 * in the panel. `DatabaseMigration39to40`, which added the columns, stays as history.
 */
@Migration
class DatabaseMigration45to46 : DatabaseMigration(
    45,
    46,
    "Remove proxy networks from servers"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        dropColumn("networkId"),
        dropColumn("networkRole")
    )

    private fun dropColumn(name: String): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // IF EXISTS is MariaDB's, and it is what makes a migration interrupted between its two
        // handlers — or a database that never had the columns — safe to run again.
        sqlClient
            .preparedQuery("ALTER TABLE `${getTablePrefix()}server` DROP COLUMN IF EXISTS `$name`")
            .execute()
            .coAwait()
    }
}
