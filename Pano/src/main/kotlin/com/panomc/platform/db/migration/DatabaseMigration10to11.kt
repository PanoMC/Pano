package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration10to11 :
    DatabaseMigration(10, 11, "Add createdAt and updatedAt columns to system property table.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        renameServerTypeColumnToTypeInServerTable(),
        renameServerVersionColumnToVersionInServerTable()
    )

    private fun renameServerTypeColumnToTypeInServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}server` RENAME COLUMN `serverType` TO `type`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }

    private fun renameServerVersionColumnToVersionInServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}server` RENAME COLUMN `serverVersion` TO `version`;"

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }
}