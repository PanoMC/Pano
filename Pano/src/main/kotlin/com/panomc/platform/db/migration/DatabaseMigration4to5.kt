package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration4to5(databaseManager: DatabaseManager) : DatabaseMigration(databaseManager) {
    override val FROM_SCHEME_VERSION = 4
    override val SCHEME_VERSION = 5
    override val SCHEME_VERSION_INFO = "Convert all permissions to uppercase."

    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        convertPermissionsToUppercase()
    )

    private fun convertPermissionsToUppercase(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query =
                "SELECT `id`, `name` FROM `${getTablePrefix()}permission`"

            val rows: RowSet<Row> = sqlClient
                .preparedQuery(query)
                .execute()
                .coAwait()

            rows.forEach {
                val updateQuery =
                    "UPDATE `${getTablePrefix()}permission` SET `name` = ? WHERE `id` = ?"

                sqlClient
                    .preparedQuery(updateQuery)
                    .execute(Tuple.of(it.getString("name").uppercase(), it.getLong("id")))
                    .coAwait()
            }
        }

}