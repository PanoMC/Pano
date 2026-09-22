package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Makes room for the two facts source-agnostic features introduced (SM-52, §2.4.17).
 *
 * `server_metric.source` says who measured a minute. It has to be on the row rather than derived,
 * because a server can change hands over the life of a chart — a Pano plugin installed halfway
 * through a week turns node rows into plugin rows from that point on, and the two are not the same
 * measurement: the plugin reports JVM heap and an exact roster, the node reports the process's
 * resident set and a twelve-name server list ping. A line that silently changed what it was
 * measuring would be a lie drawn straight. Every existing row was written from a plugin sample, so
 * the default backfills them correctly and nothing has to be touched.
 *
 * `server_backup.nodeId` becomes nullable because "no node" is now a real state: an agent-lite
 * plugin takes a backup into the server's own `backups/` directory with no daemon anywhere in the
 * story, and a sentinel id would be a foreign key pointing at a node that does not exist.
 */
@Migration
class DatabaseMigration43to44 : DatabaseMigration(
    43,
    44,
    "Add source to server metrics and allow a backup with no node"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addMetricSource(),
        allowBackupWithoutNode()
    )

    private fun addMetricSource(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // IF NOT EXISTS is MariaDB's, which is what Pano runs on, and it is what makes a migration
        // interrupted between its handlers safe to run again.
        execute(
            sqlClient,
            "ALTER TABLE `${getTablePrefix()}server_metric` " +
                "ADD COLUMN IF NOT EXISTS `source` varchar(16) NOT NULL DEFAULT 'plugin'"
        )
    }

    private fun allowBackupWithoutNode(): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // MODIFY rather than CHANGE: the column keeps its name, only its nullability moves, and
        // re-running it on an already-nullable column is a no-op rather than an error.
        execute(
            sqlClient,
            "ALTER TABLE `${getTablePrefix()}server_backup` MODIFY COLUMN `nodeId` bigint DEFAULT NULL"
        )
    }

    private suspend fun execute(sqlClient: SqlClient, query: String) {
        sqlClient.preparedQuery(query).execute().coAwait()
    }
}
