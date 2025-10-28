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
            val query = """
            ALTER TABLE `${getTablePrefix()}user`
            MODIFY COLUMN `email` VARCHAR(255) NULL UNIQUE;
        """.trimIndent()

            sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()
        }
}