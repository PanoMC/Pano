package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration14to15 : DatabaseMigration(
    14,
    15,
    "Add settings column to server table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addSettingsColumnToServerTable(),
    )

    private fun addSettingsColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            // 1️⃣ Add the column (TEXT; MySQL doesn't allow DEFAULT here)
            val addColumnQuery = """
            ALTER TABLE `${getTablePrefix()}server`
            ADD COLUMN `settings` TEXT NULL;
        """.trimIndent()

            try {
                sqlClient.preparedQuery(addColumnQuery).execute().coAwait()
            } catch (e: Exception) {
                // If the column already exists, ignore (e.g., migration re-run)
                if (!e.message.orEmpty().contains("Duplicate column name")) {
                    throw e
                }
            }

            // 2️⃣ Set all NULL/empty values to '{}'
            val updateExistingQuery = """
            UPDATE `${getTablePrefix()}server`
            SET `settings` = '{}'
            WHERE `settings` IS NULL OR `settings` = '';
        """.trimIndent()


            sqlClient.preparedQuery(updateExistingQuery).execute().coAwait()
        }
}