package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Teaches a server where its files are and a node what kind of daemon it is (the "link an existing
 * server with the Pano Agent" flow):
 *
 * - `server.inPlace`: the node runs the server from a directory that already existed, adopted where
 *   it is rather than made under the node's data directory. Removing it keeps the files, and a
 *   reinstall or a software change is refused.
 * - `server.directory`: that directory's absolute path on the node's host, as the node reports it.
 * - `node.agent`: the daemon is a Pano Agent, dedicated to the one server it was installed for. It
 *   is never listed as a node; the panel shows only its server.
 *
 * Every existing server was made by its node under the data directory and every existing node is
 * an ordinary one, which is exactly what the defaults say. `directory` stays null until a node that
 * reports it (protocol 5) says hello.
 */
@Migration
class DatabaseMigration52to53 : DatabaseMigration(
    52,
    53,
    "Add in-place location to servers and agent mode to nodes"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addColumn("server", "`inPlace` tinyint(1) NOT NULL DEFAULT 0"),
        addColumn("server", "`directory` text DEFAULT NULL"),
        addColumn("node", "`agent` tinyint(1) NOT NULL DEFAULT 0")
    )

    private fun addColumn(table: String, definition: String): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            // IF NOT EXISTS is MariaDB's, and it is what makes a migration that was interrupted
            // safe to run again.
            sqlClient
                .preparedQuery("ALTER TABLE `${getTablePrefix()}$table` ADD COLUMN IF NOT EXISTS $definition")
                .execute()
                .coAwait()
        }
}
