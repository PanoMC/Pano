package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration13to14 : DatabaseMigration(
    13,
    14,
    "Update email column as nullable in user table"
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        makeEmailNullableAndUniqueInUserTable(),
    )

    private fun makeEmailNullableAndUniqueInUserTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val table = "${getTablePrefix()}user"

            // MODIFY ... UNIQUE adds one more unique index on every run, so the index is added on its own, once.
            sqlClient.query("ALTER TABLE `$table` MODIFY COLUMN `email` VARCHAR(255) NULL").execute().coAwait()

            if (!hasUniqueIndexOn(sqlClient, table, "email")) {
                sqlClient.query("ALTER TABLE `$table` ADD UNIQUE KEY `email` (`email`)").execute().coAwait()
            }
        }
}