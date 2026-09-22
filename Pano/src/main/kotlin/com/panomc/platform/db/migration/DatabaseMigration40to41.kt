package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration40to41 : DatabaseMigration(
    40,
    41,
    "Allow a server task without a node"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        allowNullNodeIdOnServerTaskTable()
    )

    // A node bootstrap is the work of creating a node, so it cannot name one: it is the only task
    // kind with nothing to attach to until the daemon it installs pairs by itself.
    private fun allowNullNodeIdOnServerTaskTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = """
                ALTER TABLE `${getTablePrefix()}server_task`
                MODIFY COLUMN `nodeId` bigint DEFAULT NULL;
            """.trimIndent()

            sqlClient.query(query).execute().coAwait()
        }
}
