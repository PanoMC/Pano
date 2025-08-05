package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration9to10 :
    DatabaseMigration(9, 10, "Add createdAt and updatedAt columns to system property table.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addCreatedAtUpdatedAtToSystemPropertyTable(),
    )

    private fun addCreatedAtUpdatedAtToSystemPropertyTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}system_property`\n" +
                    "ADD COLUMN `createdAt` BIGINT(20) DEFAULT 0,\n" +
                    "ADD COLUMN `updatedAt` BIGINT(20) DEFAULT 0;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }
}