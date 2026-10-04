package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Keeps why a managed server's install failed on the server itself (`server.installError`), so the
 * panel can show it after the task has scrolled away and refuse a start that could only be dropped
 * (see [com.panomc.platform.node.ServerInstallFailure]).
 *
 * Existing rows are filled from their tasks: a server whose newest finished install, reinstall,
 * import or restore is a FAILED install or import, and that never had one of those end DONE, is one
 * that failed to install, and it gets that task's error. A failed reinstall is left out on purpose:
 * the node put the old server back, and old tasks are pruned, so "no DONE left" would not prove it
 * never installed. Everything else keeps null, which reads as installed.
 */
@Migration
class DatabaseMigration54to55 : DatabaseMigration(
    54,
    55,
    "Add the install failure reason to servers"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            // IF NOT EXISTS is MariaDB's, and it is what makes a migration that was interrupted
            // safe to run again.
            sqlClient
                .preparedQuery(
                    "ALTER TABLE `${getTablePrefix()}server` ADD COLUMN IF NOT EXISTS `installError` text DEFAULT NULL"
                )
                .execute()
                .coAwait()
        },
        { sqlClient: SqlClient ->
            val server = "`${getTablePrefix()}server`"
            val task = "`${getTablePrefix()}server_task`"
            val kinds = "('INSTALL', 'REINSTALL', 'IMPORT', 'RESTORE')"

            sqlClient
                .preparedQuery(
                    """
                    UPDATE $server s
                    JOIN $task f ON f.`id` = (
                        SELECT t.`id` FROM $task t
                        WHERE t.`serverId` = s.`id` AND t.`kind` IN $kinds AND t.`status` IN ('DONE', 'FAILED')
                        ORDER BY t.`createdAt` DESC, t.`id` DESC
                        LIMIT 1
                    )
                    SET s.`installError` = LEFT(COALESCE(NULLIF(TRIM(f.`error`), ''), 'The install failed.'), 2000)
                    WHERE s.`kind` = 'MANAGED'
                      AND s.`installError` IS NULL
                      AND f.`status` = 'FAILED'
                      AND f.`kind` IN ('INSTALL', 'IMPORT')
                      AND NOT EXISTS (
                          SELECT 1 FROM $task d
                          WHERE d.`serverId` = s.`id` AND d.`kind` IN $kinds AND d.`status` = 'DONE'
                      )
                    """.trimIndent()
                )
                .execute()
                .coAwait()
        }
    )
}
