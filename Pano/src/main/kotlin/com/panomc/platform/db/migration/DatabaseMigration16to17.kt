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
            val addColumnQuery = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `customName` VARCHAR(255) NULL;
        """.trimIndent()

            sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
        }
}