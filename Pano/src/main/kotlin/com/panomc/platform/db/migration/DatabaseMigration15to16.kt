package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration15to16 : DatabaseMigration(
    15,
    16,
    "Add localeCode to user table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addLocaleCodeColumnToUserTable(),
    )

    private fun addLocaleCodeColumnToUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}user", "localeCode" to "VARCHAR(10) NULL")
        }
}