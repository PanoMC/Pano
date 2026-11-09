package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration16to17 : DatabaseMigration(
    16,
    17,
    "Add customName to server table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addCustomNameColumnToServerTable(),
    )

    private fun addCustomNameColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            addMissingColumns(sqlClient, "${getTablePrefix()}server", "customName" to "VARCHAR(255) NULL")
        }
}