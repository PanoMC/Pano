package com.panomc.platform.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Migration
class DatabaseMigration5to6 : DatabaseMigration(5, 6, "Add access activity logs permission.") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addAccessActivityLogsPermission()
    )

    private fun addAccessActivityLogsPermission(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->

            val query = "INSERT INTO `${getTablePrefix()}permission` (`name`, `iconName`) VALUES (?, ?)"

            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.of(
                        "ACCESS_ACTIVITY_LOGS",
                        "fa-rectangle-list"
                    )
                ).coAwait()
        }
}