package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class DatabaseMigration25to26 : DatabaseMigration(25, 26, "Add link code to user table") {
    override val handlers: List<suspend (sqlClient: SqlClient) -> Unit> = listOf(
        { sqlClient ->
            val table = "${getTablePrefix()}user"
            if (!columnExists(sqlClient, table, "linkCode")) {
                sqlClient
                    .query("ALTER TABLE `$table` ADD `linkCode` VARCHAR(6) NULL")
                    .execute()
                    .coAwait()
            }
        },
        { sqlClient ->
            val table = "${getTablePrefix()}user"
            if (!columnExists(sqlClient, table, "linkCodeCreatedAt")) {
                sqlClient
                    .query("ALTER TABLE `$table` ADD `linkCodeCreatedAt` BIGINT NULL")
                    .execute()
                    .coAwait()
            }
        },
        { sqlClient ->
            val table = "${getTablePrefix()}user"
            if (isColumnNotNull(sqlClient, table, "password")) {
                sqlClient
                    .query("ALTER TABLE `$table` MODIFY `password` VARCHAR(255) NULL")
                    .execute()
                    .coAwait()
            }
        },
        { sqlClient ->
            val table = "${getTablePrefix()}user"
            if (isColumnNotNull(sqlClient, table, "email")) {
                // Email is already nullable in User entity, but let's ensure DB allows it if not already
                sqlClient
                    .query("ALTER TABLE `$table` MODIFY `email` VARCHAR(255) NULL")
                    .execute()
                    .coAwait()
            }
        }
    )

    private suspend fun columnExists(sqlClient: SqlClient, table: String, column: String): Boolean {
        return sqlClient.query("SHOW COLUMNS FROM `$table` LIKE '$column'").execute().coAwait().size() > 0
    }

    private suspend fun isColumnNotNull(sqlClient: SqlClient, table: String, column: String): Boolean {
        val row = sqlClient.query("SHOW COLUMNS FROM `$table` LIKE '$column'").execute().coAwait().firstOrNull()
        return row?.getString("Null") == "NO"
    }
}
