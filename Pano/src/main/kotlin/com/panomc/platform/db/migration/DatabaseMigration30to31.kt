package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration30to31 : DatabaseMigration(
    30,
    31,
    "Add remote address column to server table."
) {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addRemoteAddressColumnToServerTable()
    )

    private fun addRemoteAddressColumnToServerTable(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val tableName = "${getTablePrefix()}server"

            if (!columnExists(sqlClient, tableName, "remoteAddress")) {
                val query = """
                    ALTER TABLE `$tableName`
                    ADD COLUMN `remoteAddress` VARCHAR(255) NULL AFTER `host`;
                """.trimIndent()

                sqlClient.query(query).execute().coAwait()
            }
        }

    private suspend fun columnExists(sqlClient: SqlClient, tableName: String, columnName: String): Boolean {
        val query = """
            SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
            WHERE `TABLE_SCHEMA` = DATABASE()
              AND `TABLE_NAME` = ?
              AND `COLUMN_NAME` = ?
        """.trimIndent()

        val rows = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(tableName, columnName))
            .coAwait()

        return rows.toList()[0].getLong(0) > 0
    }
}
