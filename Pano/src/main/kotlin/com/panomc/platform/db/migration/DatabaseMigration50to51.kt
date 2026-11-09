package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Teaches a backup row about backups v2: which mode took it (`FULL` zip or incremental
 * `SNAPSHOT`), which scope it covers (`ALL`, `WORLDS`, `CUSTOM`), whether an operator pinned it out
 * of retention's reach, how many files it holds, how many bytes it actually put on disk, and the
 * include and exclude lists it was taken with (JSON arrays in text columns, like `server.jvmArgs`).
 *
 * Every existing row was a full zip of the whole directory, which is exactly what the defaults say,
 * so nothing needs backfilling. `fileCount` and `storedBytes` stay null for them: nobody counted,
 * and a zero would be a claim rather than an absence.
 */
@Migration
class DatabaseMigration50to51 : DatabaseMigration(
    50,
    51,
    "Add mode, scope, pin and storage columns to server backups"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addColumn("`mode` varchar(16) NOT NULL DEFAULT 'FULL'"),
        addColumn("`scope` varchar(16) NOT NULL DEFAULT 'ALL'"),
        addColumn("`pinned` tinyint(1) NOT NULL DEFAULT 0"),
        addColumn("`fileCount` bigint DEFAULT NULL"),
        addColumn("`storedBytes` bigint DEFAULT NULL"),
        addColumn("`include` text DEFAULT NULL"),
        addColumn("`exclude` text DEFAULT NULL")
    )

    private fun addColumn(definition: String): suspend (sqlClient: SqlClient) -> Unit = { sqlClient: SqlClient ->
        // Skipped when the column is there, so a migration that was interrupted is safe to run again.
        // definition is "`name` type ...".
        addMissingColumns(
            sqlClient,
            "${getTablePrefix()}server_backup",
            definition.substringAfter('`').substringBefore('`') to definition.substringAfter("` ")
        )
    }
}
